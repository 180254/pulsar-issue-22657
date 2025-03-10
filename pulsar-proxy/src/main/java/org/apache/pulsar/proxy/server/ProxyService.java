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

package org.apache.pulsar.proxy.server;

import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import com.google.common.collect.Sets;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.Setter;
import org.apache.pulsar.broker.ServiceConfigurationUtils;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.broker.authorization.AuthorizationService;
import org.apache.pulsar.broker.limiter.ConnectionController;
import org.apache.pulsar.broker.resources.PulsarResources;
import org.apache.pulsar.broker.stats.prometheus.PrometheusMetricsServlet;
import org.apache.pulsar.broker.stats.prometheus.PrometheusRawMetricsProvider;
import org.apache.pulsar.broker.web.plugin.servlet.AdditionalServlets;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.impl.auth.AuthenticationDisabled;
import org.apache.pulsar.common.allocator.PulsarByteBufAllocator;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.util.netty.DnsResolverUtil;
import org.apache.pulsar.common.util.netty.EventLoopUtil;
import org.apache.pulsar.metadata.api.MetadataStoreException;
import org.apache.pulsar.metadata.api.extended.MetadataStoreExtended;
import org.apache.pulsar.proxy.extensions.ProxyExtensions;
import org.apache.pulsar.proxy.stats.TopicStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulsar proxy service.
 */
public class ProxyService implements Closeable {

    private final ProxyConfiguration proxyConfig;
    private final Authentication proxyClientAuthentication;
    @Getter
    private final DnsAddressResolverGroup dnsAddressResolverGroup;
    @Getter
    private final BrokerProxyValidator brokerProxyValidator;
    private String serviceUrl;
    private String serviceUrlTls;
    private final AuthenticationService authenticationService;
    private AuthorizationService authorizationService;
    private MetadataStoreExtended localMetadataStore;
    private MetadataStoreExtended configMetadataStore;
    private PulsarResources pulsarResources;
    @Getter
    private ProxyExtensions proxyExtensions = null;

    private final EventLoopGroup acceptorGroup;
    private final EventLoopGroup workerGroup;
    private final List<EventLoopGroup> extensionsWorkerGroups = new ArrayList<>();

    private Channel listenChannel;
    private Channel listenChannelTls;

    private final DefaultThreadFactory acceptorThreadFactory = new DefaultThreadFactory("pulsar-proxy-acceptor");
    private final DefaultThreadFactory workersThreadFactory = new DefaultThreadFactory("pulsar-proxy-io");

    private BrokerDiscoveryProvider discoveryProvider;

    protected final AtomicReference<Semaphore> lookupRequestSemaphore;

    @Getter
    @Setter
    protected int proxyLogLevel;

    @Getter
    protected boolean proxyZeroCopyModeEnabled;

    private final ScheduledExecutorService statsExecutor;

    static final Gauge ACTIVE_CONNECTIONS = Gauge
            .build("pulsar_proxy_active_connections", "Number of connections currently active in the proxy").create()
            .register();

    static final Counter NEW_CONNECTIONS = Counter
            .build("pulsar_proxy_new_connections", "Counter of connections being opened in the proxy").create()
            .register();

    static final Counter REJECTED_CONNECTIONS = Counter
            .build("pulsar_proxy_rejected_connections", "Counter for connections rejected due to throttling").create()
            .register();

    static final Counter OPS_COUNTER = Counter
            .build("pulsar_proxy_binary_ops", "Counter of proxy operations").create().register();

    static final Counter BYTES_COUNTER = Counter
            .build("pulsar_proxy_binary_bytes", "Counter of proxy bytes").create().register();

    @Getter
    private final Set<ProxyConnection> clientCnxs;
    @Getter
    private final Map<String, TopicStats> topicStats;
    @Getter
    private AdditionalServlets proxyAdditionalServlets;

    private PrometheusMetricsServlet metricsServlet;
    private List<PrometheusRawMetricsProvider> pendingMetricsProviders;

    @Getter
    private final ConnectionController connectionController;

    public ProxyService(ProxyConfiguration proxyConfig,
                        AuthenticationService authenticationService) throws Exception {
        requireNonNull(proxyConfig);
        this.proxyConfig = proxyConfig;
        this.clientCnxs = Sets.newConcurrentHashSet();
        this.topicStats = new ConcurrentHashMap<>();

        this.lookupRequestSemaphore = new AtomicReference<>(
                new Semaphore(proxyConfig.getMaxConcurrentLookupRequests(), false));

        if (proxyConfig.getProxyLogLevel().isPresent()) {
            proxyLogLevel = proxyConfig.getProxyLogLevel().get();
        } else {
            proxyLogLevel = 0;
        }
        this.acceptorGroup = EventLoopUtil.newEventLoopGroup(proxyConfig.getNumAcceptorThreads(),
                false, acceptorThreadFactory);
        this.workerGroup = EventLoopUtil.newEventLoopGroup(proxyConfig.getNumIOThreads(),
                false, workersThreadFactory);
        this.authenticationService = authenticationService;

        DnsNameResolverBuilder dnsNameResolverBuilder = new DnsNameResolverBuilder()
                .channelType(EventLoopUtil.getDatagramChannelClass(workerGroup));
        DnsResolverUtil.applyJdkDnsCacheSettings(dnsNameResolverBuilder);

        dnsAddressResolverGroup = new DnsAddressResolverGroup(dnsNameResolverBuilder);

        brokerProxyValidator = new BrokerProxyValidator(dnsAddressResolverGroup.getResolver(workerGroup.next()),
                proxyConfig.getBrokerProxyAllowedHostNames(),
                proxyConfig.getBrokerProxyAllowedIPAddresses(),
                proxyConfig.getBrokerProxyAllowedTargetPorts());

        // Initialize the message protocol handlers
        proxyExtensions = ProxyExtensions.load(proxyConfig);
        proxyExtensions.initialize(proxyConfig);

        statsExecutor = Executors
                .newSingleThreadScheduledExecutor(new DefaultThreadFactory("proxy-stats-executor"));
        statsExecutor.schedule(() -> {
            this.clientCnxs.forEach(cnx -> {
                if (cnx.getDirectProxyHandler() != null
                        && cnx.getDirectProxyHandler().getInboundChannelRequestsRate() != null) {
                    cnx.getDirectProxyHandler().getInboundChannelRequestsRate().calculateRate();
                }
            });
            this.topicStats.forEach((topic, stats) -> {
                stats.calculate();
            });
        }, 60, TimeUnit.SECONDS);
        this.proxyAdditionalServlets = AdditionalServlets.load(proxyConfig);
        if (proxyConfig.getBrokerClientAuthenticationPlugin() != null) {
            proxyClientAuthentication = AuthenticationFactory.create(proxyConfig.getBrokerClientAuthenticationPlugin(),
                    proxyConfig.getBrokerClientAuthenticationParameters());
        } else {
            proxyClientAuthentication = AuthenticationDisabled.INSTANCE;
        }
        this.connectionController = new ConnectionController.DefaultConnectionController(
                proxyConfig.getMaxConcurrentInboundConnections(),
                proxyConfig.getMaxConcurrentInboundConnectionsPerIp());
    }

    public void start() throws Exception {
        if (proxyConfig.isAuthorizationEnabled() && !proxyConfig.isAuthenticationEnabled()) {
            throw new IllegalStateException("Invalid proxy configuration. Authentication must be enabled with "
                    + "authenticationEnabled=true when authorization is enabled with authorizationEnabled=true.");
        }

        if (!isBlank(proxyConfig.getMetadataStoreUrl()) && !isBlank(proxyConfig.getConfigurationMetadataStoreUrl())) {
            localMetadataStore = createLocalMetadataStore();
            configMetadataStore = createConfigurationMetadataStore();
            pulsarResources = new PulsarResources(localMetadataStore, configMetadataStore);
            discoveryProvider = new BrokerDiscoveryProvider(this.proxyConfig, pulsarResources);
            authorizationService = new AuthorizationService(PulsarConfigurationLoader.convertFrom(proxyConfig),
                    pulsarResources);
        }

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.childOption(ChannelOption.ALLOCATOR, PulsarByteBufAllocator.DEFAULT);
        bootstrap.group(acceptorGroup, workerGroup);
        bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        bootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR,
                new AdaptiveRecvByteBufAllocator(1024, 16 * 1024, 1 * 1024 * 1024));

        Class<? extends ServerSocketChannel> serverSocketChannelClass =
                EventLoopUtil.getServerSocketChannelClass(workerGroup);
        bootstrap.channel(serverSocketChannelClass);
        EventLoopUtil.enableTriggeredMode(bootstrap);

        if (proxyConfig.isProxyZeroCopyModeEnabled()
                && EpollServerSocketChannel.class.isAssignableFrom(serverSocketChannelClass)) {
            proxyZeroCopyModeEnabled = true;
        }

        bootstrap.childHandler(new ServiceChannelInitializer(this, proxyConfig, false));
        // Bind and start to accept incoming connections.
        if (proxyConfig.getServicePort().isPresent()) {
            try {
                listenChannel = bootstrap.bind(proxyConfig.getBindAddress(),
                        proxyConfig.getServicePort().get()).sync().channel();
                LOG.info("Started Pulsar Proxy at {}", listenChannel.localAddress());
            } catch (Exception e) {
                throw new IOException("Failed to bind Pulsar Proxy on port " + proxyConfig.getServicePort().get(), e);
            }
        }

        if (proxyConfig.getServicePortTls().isPresent()) {
            ServerBootstrap tlsBootstrap = bootstrap.clone();
            tlsBootstrap.childHandler(new ServiceChannelInitializer(this, proxyConfig, true));
            listenChannelTls = tlsBootstrap.bind(proxyConfig.getBindAddress(),
                    proxyConfig.getServicePortTls().get()).sync().channel();
            LOG.info("Started Pulsar TLS Proxy on {}", listenChannelTls.localAddress());
        }

        final String hostname =
                ServiceConfigurationUtils.getDefaultOrConfiguredAddress(proxyConfig.getAdvertisedAddress());

        if (proxyConfig.getServicePort().isPresent()) {
            this.serviceUrl = String.format("pulsar://%s:%d/", hostname, getListenPort().get());
        } else {
            this.serviceUrl = null;
        }

        if (proxyConfig.getServicePortTls().isPresent()) {
            this.serviceUrlTls = String.format("pulsar+ssl://%s:%d/", hostname, getListenPortTls().get());
        } else {
            this.serviceUrlTls = null;
        }

        createMetricsServlet();

        // Initialize the message protocol handlers.
        // start the protocol handlers only after the broker is ready,
        // so that the protocol handlers can access broker service properly.
        this.proxyExtensions.start(this);
        Map<String, Map<InetSocketAddress, ChannelInitializer<SocketChannel>>> protocolHandlerChannelInitializers =
                this.proxyExtensions.newChannelInitializers();
        startProxyExtensions(protocolHandlerChannelInitializers, bootstrap);
    }

    private synchronized void createMetricsServlet() {
        this.metricsServlet =
                new PrometheusMetricsServlet(proxyConfig.getMetricsServletTimeoutMs(), proxyConfig.getClusterName());
        if (pendingMetricsProviders != null) {
            pendingMetricsProviders.forEach(provider -> metricsServlet.addRawMetricsProvider(provider));
            this.pendingMetricsProviders = null;
        }
    }

    // This call is used for starting additional protocol handlers
    public void startProxyExtensions(Map<String, Map<InetSocketAddress,
            ChannelInitializer<SocketChannel>>> protocolHandlers, ServerBootstrap serverBootstrap) {

        protocolHandlers.forEach((extensionName, initializers) -> {
            initializers.forEach((address, initializer) -> {
                try {
                    startProxyExtension(extensionName, address, initializer, serverBootstrap);
                } catch (IOException e) {
                    LOG.error("{}", e.getMessage(), e.getCause());
                    throw new RuntimeException(e.getMessage(), e.getCause());
                }
            });
        });
    }

    private void startProxyExtension(String extensionName,
                                     SocketAddress address,
                                     ChannelInitializer<SocketChannel> initializer,
                                     ServerBootstrap serverBootstrap) throws IOException {
        ServerBootstrap bootstrap;
        boolean useSeparateThreadPool = proxyConfig.isUseSeparateThreadPoolForProxyExtensions();
        if (useSeparateThreadPool) {
            bootstrap = new ServerBootstrap();
            bootstrap.option(ChannelOption.SO_REUSEADDR, true);
            bootstrap.childOption(ChannelOption.ALLOCATOR, PulsarByteBufAllocator.DEFAULT);
            bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
            bootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR,
                    new AdaptiveRecvByteBufAllocator(1024, 16 * 1024, 1 * 1024 * 1024));

            EventLoopUtil.enableTriggeredMode(bootstrap);
            DefaultThreadFactory defaultThreadFactory = new DefaultThreadFactory("pulsar-ext-" + extensionName);
            EventLoopGroup dedicatedWorkerGroup =
                    EventLoopUtil.newEventLoopGroup(proxyConfig.getNumIOThreads(), false, defaultThreadFactory);
            extensionsWorkerGroups.add(dedicatedWorkerGroup);
            bootstrap.channel(EventLoopUtil.getServerSocketChannelClass(dedicatedWorkerGroup));
            bootstrap.group(this.acceptorGroup, dedicatedWorkerGroup);
        } else {
            bootstrap = serverBootstrap.clone();
        }
        bootstrap.childHandler(initializer);
        try {
            bootstrap.bind(address).sync();
        } catch (Exception e) {
            throw new IOException("Failed to bind extension `" + extensionName + "` on " + address, e);
        }
        LOG.info("Successfully bound extension `{}` on {}", extensionName, address);
    }

    public BrokerDiscoveryProvider getDiscoveryProvider() {
        return discoveryProvider;
    }

    public void close() throws IOException {
        if (listenChannel != null) {
            try {
                listenChannel.close().sync();
            } catch (InterruptedException e) {
                LOG.info("Shutdown of listenChannel interrupted");
                Thread.currentThread().interrupt();
            }
        }

        if (listenChannelTls != null) {
            try {
                listenChannelTls.close().sync();
            } catch (InterruptedException e) {
                LOG.info("Shutdown of listenChannelTls interrupted");
                Thread.currentThread().interrupt();
            }
        }

        // Don't accept any new connections
        try {
            acceptorGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            LOG.info("Shutdown of acceptorGroup interrupted");
            Thread.currentThread().interrupt();
        }

        closeAllConnections();

        dnsAddressResolverGroup.close();

        if (discoveryProvider != null) {
            discoveryProvider.close();
        }

        if (statsExecutor != null) {
            statsExecutor.shutdown();
        }

        if (proxyAdditionalServlets != null) {
            proxyAdditionalServlets.close();
            proxyAdditionalServlets = null;
        }

        resetMetricsServlet();

        if (localMetadataStore != null) {
            try {
                localMetadataStore.close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        if (configMetadataStore != null) {
            try {
                configMetadataStore.close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        try {
            workerGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            LOG.info("Shutdown of workerGroup interrupted");
            Thread.currentThread().interrupt();
        }
        for (EventLoopGroup group : extensionsWorkerGroups) {
            try {
                group.shutdownGracefully().sync();
            } catch (InterruptedException e) {
                LOG.info("Shutdown of {} interrupted", group);
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("ProxyService closed.");
    }

    private void closeAllConnections() {
        try {
            workerGroup.submit(() -> {
                // Close all the connections
                if (!clientCnxs.isEmpty()) {
                    LOG.info("Closing {} proxy connections, including connections to brokers", clientCnxs.size());
                    for (ProxyConnection clientCnx : clientCnxs) {
                        clientCnx.ctx().close();
                    }
                } else {
                    LOG.info("No proxy connections to close");
                }
            }).sync();
        } catch (InterruptedException e) {
            LOG.info("Closing of connections interrupted");
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void resetMetricsServlet() {
        metricsServlet = null;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public String getServiceUrlTls() {
        return serviceUrlTls;
    }

    public ProxyConfiguration getConfiguration() {
        return proxyConfig;
    }

    public AuthenticationService getAuthenticationService() {
        return authenticationService;
    }

    public AuthorizationService getAuthorizationService() {
        return authorizationService;
    }

    public Semaphore getLookupRequestSemaphore() {
        return lookupRequestSemaphore.get();
    }

    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    public Optional<Integer> getListenPort() {
        if (listenChannel != null) {
            return Optional.of(((InetSocketAddress) listenChannel.localAddress()).getPort());
        } else {
            return Optional.empty();
        }
    }

    public Optional<Integer> getListenPortTls() {
        if (listenChannelTls != null) {
            return Optional.of(((InetSocketAddress) listenChannelTls.localAddress()).getPort());
        } else {
            return Optional.empty();
        }
    }

    public MetadataStoreExtended createLocalMetadataStore() throws MetadataStoreException {
        return PulsarResources.createLocalMetadataStore(proxyConfig.getMetadataStoreUrl(),
                proxyConfig.getMetadataStoreSessionTimeoutMillis(),
                proxyConfig.isMetadataStoreAllowReadOnlyOperations());
    }

    public MetadataStoreExtended createConfigurationMetadataStore() throws MetadataStoreException {
        return PulsarResources.createConfigMetadataStore(proxyConfig.getConfigurationMetadataStoreUrl(),
                proxyConfig.getMetadataStoreSessionTimeoutMillis(),
                proxyConfig.isMetadataStoreAllowReadOnlyOperations());
    }

    public Authentication getProxyClientAuthenticationPlugin() {
        return this.proxyClientAuthentication;
    }

    public synchronized PrometheusMetricsServlet getMetricsServlet() {
        return metricsServlet;
    }

    public synchronized void addPrometheusRawMetricsProvider(PrometheusRawMetricsProvider metricsProvider) {
        if (metricsServlet == null) {
            if (pendingMetricsProviders == null) {
                pendingMetricsProviders = new LinkedList<>();
            }
            pendingMetricsProviders.add(metricsProvider);
        } else {
            this.metricsServlet.addRawMetricsProvider(metricsProvider);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ProxyService.class);

    protected LookupProxyHandler newLookupProxyHandler(ProxyConnection proxyConnection) {
        return new LookupProxyHandler(this, proxyConnection);
    }
}
