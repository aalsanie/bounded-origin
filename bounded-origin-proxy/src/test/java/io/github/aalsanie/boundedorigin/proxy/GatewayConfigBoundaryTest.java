package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayConfigBoundaryTest {
  @Test
  void directBuilderEnforcesConstructorOnlyBounds() {
    GatewayConfig base = baseConfig(Duration.ofSeconds(10));

    assertThrows(
        IllegalArgumentException.class,
        () -> copyOf(base).adminAddress(base.listenAddress()).build());
    assertThrows(
        IllegalArgumentException.class, () -> copyOf(base).maxRequestBodyBytes(-1).build());
    assertThrows(IllegalArgumentException.class, () -> copyOf(base).maxSpoolBytes(0).build());
    assertThrows(
        IllegalArgumentException.class, () -> copyOf(base).originMaxPendingAcquires(-1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> copyOf(base).chunkedResponseThresholdBytes(-1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> copyOf(base).failureCooldown(Duration.ofSeconds(Long.MAX_VALUE)).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> copyOf(base).originAddress(new InetSocketAddress("127.0.0.1", 0)).build());
    assertThrows(IllegalArgumentException.class, () -> copyOf(base).maxSpoolBytes(1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> copyOf(base).originResponseTimeout(Duration.ofSeconds(31)).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> copyOf(base).globalBudget(new Budget(2, 3, Duration.ofSeconds(31), 1024)).build());
  }

  @Test
  void zeroValuedBoundariesThatAreExplicitlyAllowedRemainValid() {
    GatewayConfig base = baseConfig(Duration.ofSeconds(10));
    GatewayConfig config =
        copyOf(base)
            .maxRequestBodyBytes(0)
            .originMaxPendingAcquires(0)
            .chunkedResponseThresholdBytes(0)
            .build();

    assertEquals(0, config.maxRequestBodyBytes());
    assertEquals(0, config.originMaxPendingAcquires());
    assertEquals(0, config.chunkedResponseThresholdBytes());
  }

  @Test
  void mapParserRejectsBlankRequiredValuesAndControlCharacters() {
    Map<String, String> blankOrigin = baseValues();
    blankOrigin.put("origin.host", "   ");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(blankOrigin));

    Map<String, String> blankDirectory = baseValues();
    blankDirectory.put("temporary.directory", "\t");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(blankDirectory));

    Map<String, String> controlHost = baseValues();
    controlHost.put("listen.host", "bad\tname");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(controlHost));

    Map<String, String> controlOnlyHost = baseValues();
    controlOnlyHost.put("listen.host", "bad\u0001name");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(controlOnlyHost));

    Map<String, String> explicitFalse = baseValues();
    explicitFalse.put("forwarded.trust", "FALSE");
    assertFalse(GatewayConfig.from(explicitFalse).trustForwardedHeaders());
  }

  @Test
  void defaultOriginResponseTimeoutUsesTheSmallerBound() {
    GatewayConfig longBudget = baseConfig(Duration.ofSeconds(30));
    assertEquals(Duration.ofSeconds(20), longBudget.originResponseTimeout());

    GatewayConfig shortBudget = baseConfig(Duration.ofSeconds(5));
    assertEquals(Duration.ofSeconds(5), shortBudget.originResponseTimeout());
  }

  private GatewayConfig baseConfig(Duration budgetTimeout) {
    return GatewayConfig.defaults(
        new InetSocketAddress("127.0.0.1", 18080),
        new InetSocketAddress("127.0.0.1", 18081),
        new InetSocketAddress("127.0.0.1", 18082),
        Path.of("build", "gateway-config-boundary"),
        new Budget(2, 3, budgetTimeout, 1024));
  }

  private static GatewayConfig.Builder copyOf(GatewayConfig config) {
    return GatewayConfig.builder()
        .listenAddress(config.listenAddress())
        .adminAddress(config.adminAddress())
        .originAddress(config.originAddress())
        .temporaryDirectory(config.temporaryDirectory())
        .globalBudget(config.globalBudget())
        .failureCooldown(config.failureCooldown())
        .maxCooldownEntries(config.maxCooldownEntries())
        .eventLoopThreads(config.eventLoopThreads())
        .originEventLoopThreads(config.originEventLoopThreads())
        .maxClientConnections(config.maxClientConnections())
        .serverBacklog(config.serverBacklog())
        .maxInitialLineLength(config.maxInitialLineLength())
        .maxHeaderSize(config.maxHeaderSize())
        .maxChunkSize(config.maxChunkSize())
        .maxRequestBodyBytes(config.maxRequestBodyBytes())
        .maxSpoolBytes(config.maxSpoolBytes())
        .maxSpoolFiles(config.maxSpoolFiles())
        .originMaxConnections(config.originMaxConnections())
        .originMaxPendingAcquires(config.originMaxPendingAcquires())
        .originAcquireTimeout(config.originAcquireTimeout())
        .requestTimeout(config.requestTimeout())
        .originConnectTimeout(config.originConnectTimeout())
        .originResponseTimeout(config.originResponseTimeout())
        .idleTimeout(config.idleTimeout())
        .drainTimeout(config.drainTimeout())
        .writeBufferLowWaterMark(config.writeBufferLowWaterMark())
        .writeBufferHighWaterMark(config.writeBufferHighWaterMark())
        .retryAfterSeconds(config.retryAfterSeconds())
        .ingressTrustLevel(config.ingressTrustLevel())
        .trustForwardedHeaders(config.trustForwardedHeaders())
        .chunkedResponseThresholdBytes(config.chunkedResponseThresholdBytes());
  }

  private static Map<String, String> baseValues() {
    Map<String, String> values = new HashMap<>();
    values.put("origin.host", "127.0.0.1");
    values.put("origin.port", "8080");
    values.put("temporary.directory", Path.of("build", "gateway-config-boundary").toString());
    return values;
  }
}
