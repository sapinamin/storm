/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.apache.storm.utils;

import org.apache.storm.policy.IWaitStrategy;
import org.apache.storm.metric.api.IStatefulObject;
import org.apache.storm.metric.internal.RateTracker;
import org.jctools.queues.ConcurrentCircularArrayQueue;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.SpscArrayQueue;

import java.util.ArrayList;
import java.util.HashMap;


public final class JCQueue implements IStatefulObject {

    public enum ProducerKind {SINGLE, MULTI}

    public static final Object INTERRUPT = new Object();

    private ThroughputMeter emptyMeter = new ThroughputMeter("EmptyBatch");

    private interface Inserter {
        // blocking call that can be interrupted using Thread.interrupt()
        void add(Object obj) throws InterruptedException;
        boolean tryAdd(Object obj);

        void flush() throws InterruptedException;
        boolean tryFlush();
    }

    /* Thread safe. Same instance can be used across multiple threads */
    private static class DirectInserter implements Inserter {
        private JCQueue q;

        public DirectInserter(JCQueue q) {
            this.q = q;
        }

        /** Blocking call, that can be interrupted via Thread.interrupt */
        @Override
        public void add(Object obj) throws InterruptedException {
            boolean inserted = q.tryPublishInternal(obj);
            int idleCount = 0;
            while (!inserted) {
                q.metrics.notifyInsertFailure();
                idleCount = q.backPressureWaitStrategy.idle(idleCount);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                inserted = q.tryPublishInternal(obj);
            }

        }

        /** Non-Blocking call. return value indicates success/failure */
        @Override
        public boolean tryAdd(Object obj) {
            boolean inserted = q.tryPublishInternal(obj);
            if (!inserted) {
                q.metrics.notifyInsertFailure();
                return false;
            }
            return true;
        }

        @Override
        public void flush() throws InterruptedException {
            return;
        }

        @Override
        public boolean tryFlush() {
            return true;
        }
    } // class DirectInserter

    private static class BatchInserter implements Inserter {
        private JCQueue q;
        private final int batchSz;
        private ArrayList<Object> currentBatch;

        public BatchInserter(JCQueue q, int batchSz) {
            this.q = q;
            this.batchSz = batchSz;
            this.currentBatch = new ArrayList<>(batchSz + 1);
        }

        /** Blocking call - retires till element is successfully added */
        @Override
        public void add(Object obj) throws InterruptedException {
            currentBatch.add(obj);
            if (currentBatch.size() >= batchSz) {
                flush();
            }
        }

        /** Non-Blocking call. return value indicates success/failure */
        @Override
        public boolean tryAdd(Object obj) {
            if (currentBatch.size() >= batchSz) {
                if (!tryFlush()) {
                    return false;
                }
            }
            currentBatch.add(obj);
            return true;
        }

        /** Blocking call - Does not return until at least 1 element is drained or Thread.interrupt() is received.
         *    Uses backpressure wait strategy. */
        @Override
        public void flush() throws InterruptedException {
            if (currentBatch.isEmpty()) {
                return;
            }
            int publishCount = q.tryPublishInternal(currentBatch);
            int retryCount = 0;
            while (publishCount == 0) { // retry till at least 1 element is drained
                q.metrics.notifyInsertFailure();
                retryCount = q.backPressureWaitStrategy.idle(retryCount);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                publishCount = q.tryPublishInternal(currentBatch);
            }
            currentBatch.subList(0, publishCount).clear();
        }

        /** Non blocking call. tries to flush as many as possible. Returns true if at least one from non-empty currentBatch was flushed
         *      or if currentBatch is empty. Returns false otherwise */
        @Override
        public boolean tryFlush() {
            if (currentBatch.isEmpty()) {
                return true;
            }
            int publishCount = q.tryPublishInternal(currentBatch);
            if (publishCount == 0) {
                q.metrics.notifyInsertFailure();
                return false;
            } else {
                currentBatch.subList(0, publishCount).clear();
                return true;
            }
        }
    } // class BatchInserter

    /**
     * This inner class provides methods to access the metrics of the disruptor queue.
     */
    public class QueueMetrics {
        private final RateTracker arrivalsTracker = new RateTracker(10000, 10);
        private final RateTracker insertFailuresTracker = new RateTracker(10000, 10);

        public long population() {
            return queue.size();
        }

        public long capacity() {
            return queue.capacity();
        }

        public Object getState() {
            HashMap state = new HashMap<String, Object>();

            final double arrivalRateInSecs = arrivalsTracker.reportRate();

            long tuplePop = population();

            // Assume the queue is stable, in which the arrival rate is equal to the consumption rate.
            // If this assumption does not hold, the calculation of sojourn time should also consider
            // departure rate according to Queuing Theory.
            final double sojournTime = tuplePop / Math.max(arrivalRateInSecs, 0.00001) * 1000.0;

            long cap = capacity();
            float pctFull = (1.0F * tuplePop / cap);

            state.put("capacity", cap);
            state.put("pct_full", pctFull);
            state.put("population", tuplePop);

            state.put("arrival_rate_secs", arrivalRateInSecs);
            state.put("sojourn_time_ms", sojournTime); //element sojourn time in milliseconds
            state.put("insert_failures", insertFailuresTracker.reportRate());

            return state;
        }

        public void notifyArrivals(long counts) {
            arrivalsTracker.notify(counts); // TODO: PERF: This is a perf bottleneck esp in when batchSz=1
        }

        public void notifyInsertFailure() {
            insertFailuresTracker.notify(1);
        }

        public void close() {
            arrivalsTracker.close();
            insertFailuresTracker.close();
        }

    }

    private final ConcurrentCircularArrayQueue<Object> queue;

    private final int producerBatchSz;
    private final DirectInserter directInserter = new DirectInserter(this);

    private final ThreadLocal<BatchInserter> thdLocalBatcher = new ThreadLocal<BatchInserter>();

    private final JCQueue.QueueMetrics metrics;

    private String queueName;
    private final IWaitStrategy backPressureWaitStrategy;

    public JCQueue(String queueName, int size, int inputBatchSize, IWaitStrategy backPressureWaitStrategy) {
        this(queueName, ProducerKind.MULTI, size, inputBatchSize, backPressureWaitStrategy);
    }

    public JCQueue(String queueName, ProducerKind type, int size, int inputBatchSize, IWaitStrategy backPressureWaitStrategy) {
        this.queueName = queueName;

        if (type == ProducerKind.SINGLE) {
            this.queue = new SpscArrayQueue<>(size);
        } else {
            this.queue = new MpscArrayQueue<>(size);
        }

        this.metrics = new JCQueue.QueueMetrics();

        //The batch size can be no larger than half the full queue size, to avoid contention issues.
        this.producerBatchSz = Math.max(1, Math.min(inputBatchSize, size / 2));
        this.backPressureWaitStrategy = backPressureWaitStrategy;
    }

    public String getName() {
        return queueName;
    }


    public void haltWithInterrupt() {
        tryPublishInternal(INTERRUPT);
        metrics.close();
    }

    /**
     * Non blocking. Returns immediately if Q is empty. Returns number of elements consumed from Q
     */
    public int consume(JCQueue.Consumer consumer) {
        try {
            return consumerImpl(consumer);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Non blocking. Returns immediately if Q is empty. Returns number of elements consumed from Q
     */
    private int consumerImpl(Consumer consumer) throws InterruptedException {
        int count = queue.drain(consumer);
        if (count > 0) {
            consumer.flush();
        } else {
            emptyMeter.record();
        }
        return count;
    }

    // Non Blocking. returns true/false indicating success/failure
    private boolean tryPublishInternal(Object obj) {
        if (queue.offer(obj)) {
            metrics.notifyArrivals(1);
            return true;
        }
        return false;
    }

    // Non Blocking. returns count of how many inserts succeeded
    private int tryPublishInternal(ArrayList<Object> objs) {
        MessagePassingQueue.Supplier<Object> supplier =
            new MessagePassingQueue.Supplier<Object>() {
                int i = 0;

                @Override
                public Object get() {
                    return objs.get(i++);
                }
            };
        int count = queue.fill(supplier, objs.size());
        metrics.notifyArrivals(count);
        return count;
    }

    /**
     * Blocking call. Retries till it can successfully publish the obj. Can be interrupted via Thread.interrupt().
     */
    public void publish(Object obj) throws InterruptedException {
        Inserter inserter = getInserter();
        inserter.add(obj);
    }

    /**
     * Non-blocking call, returns false if failed
     **/
    public boolean tryPublish(Object obj) {
        Inserter inserter = getInserter();
        return inserter.tryAdd(obj);
    }

    /** Non-blocking call. Bypasses any batching that may be enabled on the queue. */
    public boolean tryPublishDirect(Object obj) {
        return tryPublishInternal(obj);
    }

    private Inserter getInserter() {
        Inserter inserter;
        if (producerBatchSz > 1) {
            inserter = thdLocalBatcher.get();
            if (inserter == null) {
                BatchInserter b = new BatchInserter(this, producerBatchSz);
                inserter = b;
                thdLocalBatcher.set(b);
            }
        } else {
            inserter = directInserter;
        }
        return inserter;
    }

    /**
     * if(batchSz>1)  : Blocking call. Does not return until at least 1 element is drained or Thread.interrupt() is received
     * if(batchSz==1) : NO-OP. Returns immediately. doesnt throw.
     */
    public void flush() throws InterruptedException {
        Inserter inserter = getInserter();
        inserter.flush();
    }

    /**
     * if(batchSz>1)  : Non-Blocking call. Tries to flush as many as it can. Returns true if flushed at least 1.
     * if(batchSz==1) : This is a NO-OP. Returns true immediately.
     */
    public boolean tryFlush()  {
        Inserter inserter = getInserter();
        return inserter.tryFlush();
    }


    @Override
    public Object getState() {
        return metrics.getState();
    }


    //This method enables the metrics to be accessed from outside of the JCQueue class
    public JCQueue.QueueMetrics getMetrics() {
        return metrics;
    }

    public interface Consumer extends org.jctools.queues.MessagePassingQueue.Consumer<Object> {
        void accept(Object event);

        void flush() throws InterruptedException;
    }
}