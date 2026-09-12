package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayConfigMutationContractTest {
  @Test
  void directBuilderBoundaryFailuresPreserveTheirSpecificInvariant() {
    GatewayConfig base = base();

    assertFailure(
        "maxSpoolBytes must be positive", () -> copyOf(base).maxSpoolBytes(0).build());
    assertFailure(
        "originMaxPendingAcquires must be non-negative",
        () -> copyOf(base).originMaxPendingAcquires(-1).build());
    assertFailure(
        "maxRequestBodyBytes must be non-negative",
        () -> copyOf(base).maxRequestBodyBytes(-1).build());
    assertFailure(
        "chunkedResponseThresholdBytes must be non-negative",
        () -> copyOf(base).chunkedResponseThresholdBytes(-1).build());
    assertFailure(
        "writeBufferHighWaterMark must exceed writeBufferLowWaterMark",
        () ->
            copyOf(base)
                .writeBufferHighWaterMark(base.writeBufferLowWaterMark())
                .build());
  }

  @Test
  void parserAcceptsEveryInclusiveNumericBoundaryThatTheContractAllows() {
    Map<String, String> values = minimalValues();
    values.put("listen.port", "0");
    values.put("admin.port", "65535");
    values.put("origin.port", "65535");
    values.put("origin.max-active", "1");
    values.put("origin.max-queued", "0");
    values.put("origin.max-result-bytes", "1");
    values.put("http.max-request-body-bytes", "0");
    values.put("spool.max-bytes", "1");
    values.put("spool.max-files", "1");
    values.put("origin.max-connections", "1");
    values.put("origin.max-pending-acquires", "0");
    values.put("http.chunked-response-threshold-bytes", "0");
    values.put("forwarded.trust", "TrUe");

    GatewayConfig config = GatewayConfig.from(values);

    assertEquals(0, config.listenAddress().getPort());
    assertEquals(65535, config.adminAddress().getPort());
    assertEquals(65535, config.originAddress().getPort());
    assertEquals(1, config.globalBudget().maxActive());
    assertEquals(0, config.globalBudget().maxQueued());
    assertEquals(1, config.globalBudget().maxResultBytes());
    assertEquals(0, config.maxRequestBodyBytes());
    assertEquals(1, config.maxSpoolBytes());
    assertEquals(1, config.maxSpoolFiles());
    assertEquals(1, config.originMaxConnections());
    assertEquals(0, config.originMaxPendingAcquires());
    assertEquals(0, config.chunkedResponseThresholdBytes());
    assertTrue(config.trustForwardedHeaders());
  }

  @Test
  void parserRejectsOneStepOutsidePortAndNumericBoundariesWithSpecificMessages() {
    assertParsedFailure("origin.port", "0", "origin.port must be between 1 and 65535");
    assertParsedFailure("origin.port", "65536", "origin.port must be between 1 and 65535");
    assertParsedFailure("listen.port", "65536", "listen.port must be between 0 and 65535");
    assertParsedFailure("origin.max-active", "0", "origin.max-active must be positive");
    assertParsedFailure(
        "http.max-request-body-bytes",
        "-1",
        "http.max-request-body-bytes must be non-negative");
    assertParsedFailure("spool.max-bytes", "0", "spool.max-bytes must be positive");
  }

  @Test
  void defaultResponseTimeoutUsesTheLeftValueWhenBothBoundsAreEqual() {
    GatewayConfig config =
        GatewayConfig.defaults(
            new InetSocketAddress("127.0.0.1", 18080),
            new InetSocketAddress("127.0.0.1", 18081),
            new InetSocketAddress("127.0.0.1", 18082),
            Path.of("build", "gateway-config-equal-timeout"),
            new Budget(1, 0, Duration.ofSeconds(20), 1));

    assertEquals(Duration.ofSeconds(20), config.originResponseTimeout());
  }

  private static GatewayConfig base() {
    return GatewayConfig.defaults(
        new InetSocketAddress("127.0.0.1", 18080),
        new InetSocketAddress("127.0.0.1", 18081),
        new InetSocketAddress("127.0.0.1", 18082),
        Path.of("build", "gateway-config-mutation"),
        new Budget(2, 3, Duration.ofSeconds(10), 1024));
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

  private static Map<String, String> minimalValues() {
    Map<String, String> values = new HashMap<>();
    values.put("origin.host", "127.0.0.1");
    values.put("origin.port", "8080");
    values.put("temporary.directory", Path.of("build", "gateway-config-mutation").toString());
    return values;
  }

  private static void assertParsedFailure(String key, String value, String message) {
    Map<String, String> values = minimalValues();
    values.put(key, value);
    assertFailure(message, () -> GatewayConfig.from(values));
  }

  private static void assertFailure(String message, Runnable action) {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
    assertEquals(message, failure.getMessage());
  }
}
