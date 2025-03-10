/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.apache.bookkeeper.mledger.Position;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.service.BrokerServiceException.NamingException;
import org.apache.pulsar.broker.service.BrokerServiceException.TopicBusyException;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.Backoff;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.FutureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractReplicator {

    protected final BrokerService brokerService;
    protected final String localTopicName;
    protected final String localCluster;
    protected final String remoteTopicName;
    protected final String remoteCluster;
    protected final PulsarClientImpl replicationClient;
    protected final PulsarClientImpl client;
    protected String replicatorId;
    protected final Topic localTopic;

    protected volatile ProducerImpl producer;
    public static final String REPL_PRODUCER_NAME_DELIMITER = "-->";

    protected final int producerQueueSize;
    protected final ProducerBuilder<byte[]> producerBuilder;

    protected final Backoff backOff = new Backoff(100, TimeUnit.MILLISECONDS, 1, TimeUnit.MINUTES, 0,
            TimeUnit.MILLISECONDS);

    protected final String replicatorPrefix;

    protected static final AtomicReferenceFieldUpdater<AbstractReplicator, State> STATE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractReplicator.class, State.class, "state");
    private volatile State state = State.Stopped;

    protected enum State {
        Stopped, Starting, Started, Stopping
    }

    public AbstractReplicator(String localCluster, Topic localTopic, String remoteCluster, String remoteTopicName,
                              String replicatorPrefix, BrokerService brokerService, PulsarClientImpl replicationClient)
            throws PulsarServerException {
        this.brokerService = brokerService;
        this.localTopic = localTopic;
        this.localTopicName = localTopic.getName();
        this.replicatorPrefix = replicatorPrefix;
        this.localCluster = localCluster.intern();
        this.remoteTopicName = remoteTopicName;
        this.remoteCluster = remoteCluster.intern();
        this.replicationClient = replicationClient;
        this.client = (PulsarClientImpl) brokerService.pulsar().getClient();
        this.producer = null;
        this.producerQueueSize = brokerService.pulsar().getConfiguration().getReplicationProducerQueueSize();
        this.replicatorId = String.format("%s | %s",
                StringUtils.equals(localTopicName, remoteTopicName) ? localTopicName :
                        localTopicName + "-->" + remoteTopicName,
                StringUtils.equals(localCluster, remoteCluster) ? localCluster : localCluster + "-->" + remoteCluster
        );
        this.producerBuilder = replicationClient.newProducer(Schema.AUTO_PRODUCE_BYTES()) //
                .topic(remoteTopicName)
                .messageRoutingMode(MessageRoutingMode.SinglePartition)
                .enableBatching(false)
                .sendTimeout(0, TimeUnit.SECONDS) //
                .maxPendingMessages(producerQueueSize) //
                .producerName(getProducerName());
        STATE_UPDATER.set(this, State.Stopped);
    }

    protected abstract String getProducerName();

    protected abstract void readEntries(org.apache.pulsar.client.api.Producer<byte[]> producer);

    protected abstract Position getReplicatorReadPosition();

    protected abstract long getNumberOfEntriesInBacklog();

    protected abstract void disableReplicatorRead();

    public String getRemoteCluster() {
        return remoteCluster;
    }

    // This method needs to be synchronized with disconnects else if there is a disconnect followed by startProducer
    // the end result can be disconnect.
    public synchronized void startProducer() {
        if (STATE_UPDATER.get(this) == State.Stopping) {
            long waitTimeMs = backOff.next();
            if (log.isDebugEnabled()) {
                log.debug(
                        "[{}] waiting for producer to close before attempting to reconnect, retrying in {} s",
                        replicatorId, waitTimeMs / 1000.0);
            }
            // BackOff before retrying
            brokerService.executor().schedule(this::checkTopicActiveAndRetryStartProducer, waitTimeMs,
                    TimeUnit.MILLISECONDS);
            return;
        }
        State state = STATE_UPDATER.get(this);
        if (!STATE_UPDATER.compareAndSet(this, State.Stopped, State.Starting)) {
            if (state == State.Started) {
                // Already running
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Replicator was already running", replicatorId);
                }
            } else {
                log.info("[{}] Replicator already being started. Replicator state: {}", replicatorId, state);
            }

            return;
        }

        log.info("[{}] Starting replicator", replicatorId);
        producerBuilder.createAsync().thenAccept(producer -> {
            readEntries(producer);
        }).exceptionally(ex -> {
            if (STATE_UPDATER.compareAndSet(this, State.Starting, State.Stopped)) {
                long waitTimeMs = backOff.next();
                log.warn("[{}] Failed to create remote producer ({}), retrying in {} s",
                        replicatorId, ex.getMessage(), waitTimeMs / 1000.0);

                // BackOff before retrying
                brokerService.executor().schedule(this::checkTopicActiveAndRetryStartProducer, waitTimeMs,
                        TimeUnit.MILLISECONDS);
            } else {
                log.warn("[{}] Failed to create remote producer. Replicator state: {}", replicatorId,
                        STATE_UPDATER.get(this), ex);
            }
            return null;
        });

    }

    protected void checkTopicActiveAndRetryStartProducer() {
        isLocalTopicActive().thenAccept(isTopicActive -> {
            if (isTopicActive) {
                startProducer();
            }
        }).exceptionally(ex -> {
            log.warn("[{}] Stop retry to create producer due to topic load fail. Replicator state: {}", replicatorId,
                    STATE_UPDATER.get(this), ex);
            return null;
        });
    }

    protected CompletableFuture<Boolean> isLocalTopicActive() {
        CompletableFuture<Optional<Topic>> topicFuture = brokerService.getTopics().get(localTopicName);
        if (topicFuture == null){
            return CompletableFuture.completedFuture(false);
        }
        return topicFuture.thenApplyAsync(optional -> {
            if (optional.isEmpty()) {
                return false;
            }
            return optional.get() == localTopic;
        }, brokerService.executor());
    }

    protected synchronized CompletableFuture<Void> closeProducerAsync() {
        if (producer == null) {
            STATE_UPDATER.set(this, State.Stopped);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = producer.closeAsync();
        return future.thenRun(() -> {
            STATE_UPDATER.set(this, State.Stopped);
            this.producer = null;
            // deactivate further read
            disableReplicatorRead();
        }).exceptionally(ex -> {
            long waitTimeMs = backOff.next();
            log.warn(
                    "[{}] Exception: '{}' occurred while trying to close the producer."
                            + " retrying again in {} s",
                    replicatorId, ex.getMessage(), waitTimeMs / 1000.0);
            // BackOff before retrying
            brokerService.executor().schedule(this::closeProducerAsync, waitTimeMs, TimeUnit.MILLISECONDS);
            return null;
        });
    }


    public CompletableFuture<Void> disconnect() {
        return disconnect(false);
    }

    public synchronized CompletableFuture<Void> disconnect(boolean failIfHasBacklog) {
        if (failIfHasBacklog && getNumberOfEntriesInBacklog() > 0) {
            CompletableFuture<Void> disconnectFuture = new CompletableFuture<>();
            disconnectFuture.completeExceptionally(new TopicBusyException("Cannot close a replicator with backlog"));
            if (log.isDebugEnabled()) {
                log.debug("[{}] Replicator disconnect failed since topic has backlog", replicatorId);
            }
            return disconnectFuture;
        }

        if (STATE_UPDATER.get(this) == State.Stopping) {
            // Do nothing since the all "STATE_UPDATER.set(this, Stopping)" instructions are followed by
            // closeProducerAsync()
            // which will at some point change the state to stopped
            return CompletableFuture.completedFuture(null);
        }

        if (STATE_UPDATER.compareAndSet(this, State.Starting, State.Stopping)
                || STATE_UPDATER.compareAndSet(this, State.Started, State.Stopping)) {
            log.info("[{}] Disconnect replicator at position {} with backlog {}", replicatorId,
                    getReplicatorReadPosition(), getNumberOfEntriesInBacklog());
        }

        return closeProducerAsync();
    }

    public CompletableFuture<Void> remove() {
        // No-op
        return CompletableFuture.completedFuture(null);
    }

    protected boolean isWritable() {
        ProducerImpl producer = this.producer;
        return producer != null && producer.isWritable();
    }

    public static String getRemoteCluster(String remoteCursor) {
        String[] split = remoteCursor.split("\\.");
        return split[split.length - 1];
    }

    public static String getReplicatorName(String replicatorPrefix, String cluster) {
        return (replicatorPrefix + "." + cluster).intern();
    }

    /**
     * Replication can't be started on root-partitioned-topic to avoid producer startup conflict.
     *
     * <pre>
     * eg:
     * if topic : persistent://prop/cluster/ns/my-topic is a partitioned topic with 2 partitions then
     * broker explicitly creates replicator producer for: "my-topic-partition-1" and "my-topic-partition-2".
     *
     * However, if broker tries to start producer with root topic "my-topic" then client-lib internally
     * creates individual producers for "my-topic-partition-1" and "my-topic-partition-2" which creates
     * conflict with existing
     * replicator producers.
     * </pre>
     *
     * Therefore, replicator can't be started on root-partition topic which can internally create multiple partitioned
     * producers.
     *
     * @param topic
     * @param brokerService
     */
    public static CompletableFuture<Void> validatePartitionedTopicAsync(String topic, BrokerService brokerService) {
        TopicName topicName = TopicName.get(topic);
        return brokerService.pulsar().getPulsarResources().getNamespaceResources().getPartitionedTopicResources()
            .partitionedTopicExistsAsync(topicName).thenCompose(isPartitionedTopic -> {
                if (isPartitionedTopic) {
                    String s = topicName
                            + " is a partitioned-topic and replication can't be started for partitioned-producer ";
                    log.error(s);
                    return FutureUtil.failedFuture(new NamingException(s));
                }
                return CompletableFuture.completedFuture(null);
            });
    }

    private static final Logger log = LoggerFactory.getLogger(AbstractReplicator.class);

    public State getState() {
        return state;
    }
}
