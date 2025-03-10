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
package org.apache.pulsar.testclient;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.FileUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.util.DirectMemoryUtils;
import org.slf4j.Logger;

/**
 * Utility for test clients.
 */
@UtilityClass
public class PerfClientUtils {

    private static volatile  Consumer<Integer> exitProcedure = System::exit;

    public static void setExitProcedure(Consumer<Integer> exitProcedure) {
        PerfClientUtils.exitProcedure = Objects.requireNonNull(exitProcedure);
    }

    public static void exit(int code) {
        exitProcedure.accept(code);
    }

    /**
     * Print useful JVM information, you need this information in order to be able
     * to compare the results of executions in different environments.
     * @param log
     */
    public static void printJVMInformation(Logger log) {
        log.info("JVM args {}", ManagementFactory.getRuntimeMXBean().getInputArguments());
        log.info("Netty max memory (PlatformDependent.maxDirectMemory()) {}",
                FileUtils.byteCountToDisplaySize(DirectMemoryUtils.jvmMaxDirectMemory()));
        log.info("JVM max heap memory (Runtime.getRuntime().maxMemory()) {}",
                FileUtils.byteCountToDisplaySize(Runtime.getRuntime().maxMemory()));
    }

    public static ClientBuilder createClientBuilderFromArguments(PerformanceBaseArguments arguments)
            throws PulsarClientException.UnsupportedAuthenticationException {

        ClientBuilder clientBuilder = PulsarClient.builder()
                .serviceUrl(arguments.serviceURL)
                .connectionsPerBroker(arguments.maxConnections)
                .ioThreads(arguments.ioThreads)
                .statsInterval(arguments.statsIntervalSeconds, TimeUnit.SECONDS)
                .enableBusyWait(arguments.enableBusyWait)
                .listenerThreads(arguments.listenerThreads)
                .tlsTrustCertsFilePath(arguments.tlsTrustCertsFilePath)
                .maxLookupRequests(arguments.maxLookupRequest)
                .proxyServiceUrl(arguments.proxyServiceURL, arguments.proxyProtocol);

        if (isNotBlank(arguments.authPluginClassName)) {
            clientBuilder.authentication(arguments.authPluginClassName, arguments.authParams);
        }

        if (arguments.tlsAllowInsecureConnection != null) {
            clientBuilder.allowTlsInsecureConnection(arguments.tlsAllowInsecureConnection);
        }

        if (arguments.tlsHostnameVerificationEnable != null) {
            clientBuilder.enableTlsHostnameVerification(arguments.tlsHostnameVerificationEnable);
        }

        if (isNotBlank(arguments.listenerName)) {
            clientBuilder.listenerName(arguments.listenerName);
        }
        return clientBuilder;
    }

    public static PulsarAdminBuilder createAdminBuilderFromArguments(PerformanceBaseArguments arguments,
                                                                     final String adminUrl)
            throws PulsarClientException.UnsupportedAuthenticationException {

        PulsarAdminBuilder pulsarAdminBuilder = PulsarAdmin.builder()
                .serviceHttpUrl(adminUrl)
                .tlsTrustCertsFilePath(arguments.tlsTrustCertsFilePath);

        if (isNotBlank(arguments.authPluginClassName)) {
            pulsarAdminBuilder.authentication(arguments.authPluginClassName, arguments.authParams);
        }

        if (arguments.tlsAllowInsecureConnection != null) {
            pulsarAdminBuilder.allowTlsInsecureConnection(arguments.tlsAllowInsecureConnection);
        }

        if (arguments.tlsHostnameVerificationEnable != null) {
            pulsarAdminBuilder.enableTlsHostnameVerification(arguments.tlsHostnameVerificationEnable);
        }

        return pulsarAdminBuilder;
    }


}
