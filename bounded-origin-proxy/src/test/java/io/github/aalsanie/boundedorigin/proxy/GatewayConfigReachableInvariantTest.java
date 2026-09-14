package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayConfigReachableInvariantTest {
  @Test
  void directBuilderRejectsZeroForPositiveIntegerFields() {
    GatewayConfig base =
        GatewayConfig.defaults(
            new InetSocketAddress("127.0.0.1", 18080),
            new InetSocketAddress("127.0.0.1", 18081),
            new InetSocketAddress("127.0.0.1", 18082),
            Path.of("build", "gateway-config-positive-int"),
            new Budget(2, 3, Duration.ofSeconds(10), 1024));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            GatewayConfig.builder()
                .listenAddress(base.listenAddress())
                .adminAddress(base.adminAddress())
                .originAddress(base.originAddress())
                .temporaryDirectory(base.temporaryDirectory())
                .globalBudget(base.globalBudget())
                .failureCooldown(base.failureCooldown())
                .maxCooldownEntries(base.maxCooldownEntries())
                .eventLoopThreads(base.eventLoopThreads())
                .originEventLoopThreads(base.originEventLoopThreads())
                .maxClientConnections(0)
                .serverBacklog(base.serverBacklog())
                .maxInitialLineLength(base.maxInitialLineLength())
                .maxHeaderSize(base.maxHeaderSize())
                .maxChunkSize(base.maxChunkSize())
                .maxRequestBodyBytes(base.maxRequestBodyBytes())
                .maxSpoolBytes(base.maxSpoolBytes())
                .maxSpoolFiles(base.maxSpoolFiles())
                .originMaxConnections(base.originMaxConnections())
                .originMaxPendingAcquires(base.originMaxPendingAcquires())
                .originAcquireTimeout(base.originAcquireTimeout())
                .requestTimeout(base.requestTimeout())
                .originConnectTimeout(base.originConnectTimeout())
                .originResponseTimeout(base.originResponseTimeout())
                .idleTimeout(base.idleTimeout())
                .drainTimeout(base.drainTimeout())
                .writeBufferLowWaterMark(base.writeBufferLowWaterMark())
                .writeBufferHighWaterMark(base.writeBufferHighWaterMark())
                .retryAfterSeconds(base.retryAfterSeconds())
                .ingressTrustLevel(base.ingressTrustLevel())
                .trustForwardedHeaders(base.trustForwardedHeaders())
                .chunkedResponseThresholdBytes(base.chunkedResponseThresholdBytes())
                .build());
  }
}
