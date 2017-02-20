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

package org.apache.storm.perf.utils;

import org.apache.storm.LocalCluster;
import org.apache.storm.generated.Nimbus;
import org.apache.storm.utils.NimbusClient;
import org.apache.storm.utils.Utils;
import org.apache.log4j.Logger;

import java.io.PrintWriter;
import java.util.*;


public class BasicMetricsCollector  {

    private LocalCluster localCluster;
    private Nimbus.Client client;
    private PrintWriter dataWriter;
    private long startTime=0;

    public static enum MetricsItem {
        TOPOLOGY_STATS,
        THROUGHPUT,
        SPOUT_THROUGHPUT,
        SPOUT_LATENCY,
        ALL
    }


    /* headers */
    public static final String TIME = "time(s)";
    public static final String TIME_FORMAT = "%d";
    public static final String TOTAL_SLOTS = "total_slots";
    public static final String USED_SLOTS = "used_slots";
    public static final String WORKERS = "workers";
    public static final String TASKS = "tasks";
    public static final String EXECUTORS = "executors";
    public static final String TRANSFERRED = "transferred (messages)";
    public static final String THROUGHPUT = "throughput (messages/s)";
    public static final String SPOUT_EXECUTORS = "spout_executors";
    public static final String SPOUT_TRANSFERRED = "spout_transferred (messages)";
    public static final String SPOUT_ACKED = "spout_acked (messages)";
    public static final String SPOUT_THROUGHPUT = "spout_throughput (messages/s)";
    public static final String SPOUT_AVG_COMPLETE_LATENCY = "spout_avg_complete_latency(ms)";
    public static final String SPOUT_AVG_LATENCY_FORMAT = "%.1f";
    public static final String SPOUT_MAX_COMPLETE_LATENCY = "spout_max_complete_latency(ms)";
    public static final String SPOUT_MAX_LATENCY_FORMAT = "%.1f";
    private static final Logger LOG = Logger.getLogger(BasicMetricsCollector.class);
    final MetricsCollectorConfig config;
    //    final StormTopology topology;
    final Set<String> header = new LinkedHashSet<String>();
    final Map<String, String> metrics = new HashMap<String, String>();
    int lineNumber = 0;

    final boolean collectTopologyStats;
    final boolean collectExecutorStats;
    final boolean collectThroughput;

    final boolean collectSpoutThroughput;
    final boolean collectSpoutLatency;

    private MetricsSample lastSample;
    private MetricsSample curSample;
    private double maxLatency = 0;

    boolean first = true;

    public BasicMetricsCollector(Nimbus.Client client, String topoName, Map stormConfig) {
        this(topoName, stormConfig);
        this.client= client;
        this.localCluster = null;
    }

    public BasicMetricsCollector(LocalCluster localCluster, String topoName, Map stormConfig) {
        this(topoName, stormConfig);
        this.client= null;
        this.localCluster = localCluster;
    }

    private BasicMetricsCollector(String topoName, Map stormConfig) {
        Set<MetricsItem> items = getMetricsToCollect();
        this.config = new MetricsCollectorConfig(topoName, stormConfig);
        collectTopologyStats = collectTopologyStats(items);
        collectExecutorStats = collectExecutorStats(items);
        collectThroughput = collectThroughput(items);
        collectSpoutThroughput = collectSpoutThroughput(items);
        collectSpoutLatency = collectSpoutLatency(items);
        dataWriter = new PrintWriter(System.err);
    }


    private Set<MetricsItem>  getMetricsToCollect() {
        Set<MetricsItem> result = new HashSet<>();
        result.add(MetricsItem.ALL);
        return result;
    }

    public void collect(Nimbus.Client client) {
        try {
            if (!first) {
                this.lastSample = this.curSample;
                this.curSample = MetricsSample.factory(client, config.name);
                updateStats(dataWriter);
                writeLine(dataWriter);
            } else {
                LOG.info("Getting baseline metrics sample.");
                writeHeader(dataWriter);
                this.curSample = MetricsSample.factory(client, config.name);
                first = false;
                startTime = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LOG.error("storm metrics failed! ", e);
        }
    }

    public void collect(LocalCluster localCluster) {
        try {
            if (!first) {
                this.lastSample = this.curSample;
                this.curSample = MetricsSample.factory(localCluster, config.name);
                updateStats(dataWriter);
                writeLine(dataWriter);
            } else {
                LOG.info("Getting baseline metrics sample.");
                writeHeader(dataWriter);
                this.curSample = MetricsSample.factory(localCluster, config.name);
                first = false;
                startTime = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LOG.error("storm metrics failed! ", e);
        }
    }

    public void close() {
        dataWriter.close();
    }

    public Nimbus.Client getNimbusClient(Map stormConfig) {
        return NimbusClient.getConfiguredClient(stormConfig).getClient();
    }

    boolean updateStats(PrintWriter writer)
            throws Exception {
        if (collectTopologyStats) {
            updateTopologyStats();
        }
        if (collectExecutorStats) {
            updateExecutorStats();
        }
        return true;
    }

    void updateTopologyStats() {
        long timeTotal = System.currentTimeMillis() - startTime;
        int numWorkers = this.curSample.getNumWorkers();
        int numExecutors = this.curSample.getNumExecutors();
        int numTasks = this.curSample.getNumTasks();
        metrics.put(TIME, String.format(TIME_FORMAT, timeTotal / 1000));
        metrics.put(WORKERS, Integer.toString(numWorkers));
        metrics.put(EXECUTORS, Integer.toString(numExecutors));
        metrics.put(TASKS, Integer.toString(numTasks));
    }

    void updateExecutorStats() {
        long timeDiff = this.curSample.getSampleTime() - this.lastSample.getSampleTime();
        long transferredDiff = this.curSample.getTotalTransferred() - this.lastSample.getTotalTransferred();
        long throughput = transferredDiff / (timeDiff / 1000);

        long spoutDiff = this.curSample.getSpoutTransferred() - this.lastSample.getSpoutTransferred();
        long spoutAckedDiff = this.curSample.getTotalAcked() - this.lastSample.getTotalAcked();
        long spoutThroughput = spoutDiff / (timeDiff / 1000);

        if (collectThroughput) {
            metrics.put(TRANSFERRED, Long.toString(transferredDiff));
            metrics.put(THROUGHPUT, Long.toString(throughput));
        }

        if (collectSpoutThroughput) {

            metrics.put(SPOUT_EXECUTORS, Integer.toString(this.curSample.getSpoutExecutors()));
            metrics.put(SPOUT_TRANSFERRED, Long.toString(spoutDiff));
            metrics.put(SPOUT_ACKED, Long.toString(spoutAckedDiff));
            metrics.put(SPOUT_THROUGHPUT, Long.toString(spoutThroughput));
        }


        if (collectSpoutLatency) {
            double latency = this.curSample.getTotalLatency();
            if (latency > this.maxLatency) {
                this.maxLatency = latency;
            }
            metrics.put(SPOUT_AVG_COMPLETE_LATENCY,
                    String.format(SPOUT_AVG_LATENCY_FORMAT, latency));
            metrics.put(SPOUT_MAX_COMPLETE_LATENCY,
                    String.format(SPOUT_MAX_LATENCY_FORMAT, this.maxLatency));

        }
    }


    void writeHeader(PrintWriter writer) {
        header.add(TIME);
        if (collectTopologyStats) {
            header.add(WORKERS);
            header.add(TASKS);
            header.add(EXECUTORS);
        }

        if (collectThroughput) {
            header.add(TRANSFERRED);
            header.add(THROUGHPUT);
        }

        if (collectSpoutThroughput) {
            header.add(SPOUT_EXECUTORS);
            header.add(SPOUT_TRANSFERRED);
            header.add(SPOUT_ACKED);
            header.add(SPOUT_THROUGHPUT);
        }

        if (collectSpoutLatency) {
            header.add(SPOUT_AVG_COMPLETE_LATENCY);
            header.add(SPOUT_MAX_COMPLETE_LATENCY);
        }

        String str = Utils.join(header, ",");
        writer.println(str);
        writer.println("------------------------------------------------------------------------------------------------------------------");
        writer.flush();
    }

    void writeLine(PrintWriter writer) {
        List<String> line = new LinkedList<String>();
        for (String h : header) {
            line.add(metrics.get(h));
        }
        String str = Utils.join(line, ",");
        writer.println(str);
        writer.flush();
    }


    boolean collectTopologyStats(Set<MetricsItem> items) {
        return items.contains(MetricsItem.ALL) ||
                items.contains(MetricsItem.TOPOLOGY_STATS);
    }

    boolean collectExecutorStats(Set<MetricsItem> items) {
        return items.contains(MetricsItem.ALL) ||
                items.contains(MetricsItem.THROUGHPUT) ||
                items.contains(MetricsItem.SPOUT_LATENCY);
    }

    boolean collectThroughput(Set<MetricsItem> items) {
        return items.contains(MetricsItem.ALL) ||
                items.contains(MetricsItem.THROUGHPUT);
    }

    boolean collectSpoutThroughput(Set<MetricsItem> items) {
        return items.contains(MetricsItem.ALL) ||
                items.contains(MetricsItem.SPOUT_THROUGHPUT);
    }

    boolean collectSpoutLatency(Set<MetricsItem> items) {
        return items.contains(MetricsItem.ALL) ||
                items.contains(MetricsItem.SPOUT_LATENCY);
    }



    public static class MetricsCollectorConfig {
        private static final Logger LOG = Logger.getLogger(MetricsCollectorConfig.class);

        // storm configuration
        public final Map stormConfig;
        // storm topology name
        public final String name;
        // benchmark label
        public final String label;
        // How often should metrics be collected
        public final int pollInterval = 60; // sec
        // How long should the benchmark run for
        public final int totalTime = 600;   // sec


        public MetricsCollectorConfig(String topoName, Map stormConfig) {
            this.stormConfig = stormConfig;
            String labelStr = (String) stormConfig.get("benchmark.label");
            this.name = topoName;
            if (labelStr == null) {
                LOG.warn("'benchmark.label' not found in config. Defaulting to topology name");
                labelStr = this.name;
            }
            this.label = labelStr;
        }
    } // MetricsCollectorConfig

}