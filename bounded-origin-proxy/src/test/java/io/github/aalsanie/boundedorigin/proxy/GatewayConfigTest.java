package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GatewayConfigTest {
  @Test
  void defaultsAreBoundedAndInternetIngressIsUntrusted() {
    Budget budget = new Budget(4, 8, Duration.ofSeconds(10), 4 * 1024 * 1024);
    GatewayConfig config =
        GatewayConfig.defaults(
            new InetSocketAddress("127.0.0.1", 0),
            new InetSocketAddress("127.0.0.1", 0),
            new InetSocketAddress("127.0.0.1", 8080),
            Path.of("build", "gateway-spool"),
            budget);

    assertEquals(TrustLevel.UNTRUSTED, config.ingressTrustLevel());
    assertFalse(config.trustForwardedHeaders());
    assertEquals(budget, config.globalBudget());
    assertEquals(8080, config.originAddress().getPort());
    assertEquals(4, config.originMaxConnections());
    assertEquals(8, config.originMaxPendingAcquires());
    assertEquals(8L * 1024 * 1024, config.maxRequestBodyBytes());
    assertEquals(64 * 1024, config.chunkedResponseThresholdBytes());
  }

  @Test
  void strictMapParserAcceptsEverySupportedOverride() {
    Map<String, String> values = baseValues();
    values.putAll(
        Map.ofEntries(
            Map.entry("listen.host", "127.0.0.1"),
            Map.entry("listen.port", "0"),
            Map.entry("admin.host", "127.0.0.2"),
            Map.entry("admin.port", "0"),
            Map.entry("ingress.trust", "trusted"),
            Map.entry("forwarded.trust", "true"),
            Map.entry("event-loop.threads", "3"),
            Map.entry("origin.event-loop.threads", "2"),
            Map.entry("frontend.max-connections", "99"),
            Map.entry("server.backlog", "77"),
            Map.entry("http.max-initial-line-bytes", "2048"),
            Map.entry("http.max-header-bytes", "4096"),
            Map.entry("http.chunk-bytes", "1024"),
            Map.entry("http.max-request-body-bytes", "1024"),
            Map.entry("http.chunked-response-threshold-bytes", "512"),
            Map.entry("spool.max-bytes", "8192"),
            Map.entry("spool.max-files", "7"),
            Map.entry("origin.max-connections", "2"),
            Map.entry("origin.max-pending-acquires", "0"),
            Map.entry("origin.acquire-timeout", "PT0.3S"),
            Map.entry("origin.max-active", "2"),
            Map.entry("origin.max-queued", "1"),
            Map.entry("origin.max-execution-duration", "PT2S"),
            Map.entry("origin.max-result-bytes", "4096"),
            Map.entry("origin.connect-timeout", "PT0.5S"),
            Map.entry("origin.response-timeout", "PT1S"),
            Map.entry("origin.failure-cooldown", "PT0.2S"),
            Map.entry("origin.max-cooldown-entries", "16"),
            Map.entry("request.timeout", "PT3S"),
            Map.entry("idle.timeout", "PT4S"),
            Map.entry("drain.timeout", "PT5S"),
            Map.entry("overload.retry-after-seconds", "2"),
            Map.entry("downstream.write-low-watermark-bytes", "1024"),
            Map.entry("downstream.write-high-watermark-bytes", "2048")));

    GatewayConfig config = GatewayConfig.from(values);

    assertEquals(TrustLevel.TRUSTED, config.ingressTrustLevel());
    assertEquals(3, config.eventLoopThreads());
    assertEquals(2, config.originEventLoopThreads());
    assertEquals(99, config.maxClientConnections());
    assertEquals(77, config.serverBacklog());
    assertEquals(2048, config.maxInitialLineLength());
    assertEquals(4096, config.maxHeaderSize());
    assertEquals(1024, config.maxChunkSize());
    assertEquals(8192, config.maxSpoolBytes());
    assertEquals(7, config.maxSpoolFiles());
    assertEquals(Duration.ofMillis(300), config.originAcquireTimeout());
    assertEquals(2, config.retryAfterSeconds());
  }

  @ParameterizedTest
  @MethodSource("invalidConfigurations")
  void strictMapParserRejectsInvalidConfiguration(String key, String value) {
    Map<String, String> values = baseValues();
    values.put(key, value);
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(values));
  }

  @Test
  void unknownAndMissingRequiredPropertiesFailClosed() {
    Map<String, String> unknown = baseValues();
    unknown.put("origin.magic", "true");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(unknown));

    Map<String, String> missingOrigin = baseValues();
    missingOrigin.remove("origin.host");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(missingOrigin));

    Map<String, String> missingDirectory = baseValues();
    missingDirectory.remove("temporary.directory");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(missingDirectory));
  }

  @Test
  void crossFieldBoundsAreRejected() {
    Map<String, String> tooLittleSpool = baseValues();
    tooLittleSpool.put("spool.max-bytes", "1");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(tooLittleSpool));

    Map<String, String> invertedWatermarks = baseValues();
    invertedWatermarks.put("downstream.write-low-watermark-bytes", "4096");
    invertedWatermarks.put("downstream.write-high-watermark-bytes", "2048");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(invertedWatermarks));

    Map<String, String> originLongerThanRequest = baseValues();
    originLongerThanRequest.put("origin.response-timeout", "PT31S");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(originLongerThanRequest));

    Map<String, String> computeLongerThanRequest = baseValues();
    computeLongerThanRequest.put("origin.max-execution-duration", "PT31S");
    assertThrows(
        IllegalArgumentException.class, () -> GatewayConfig.from(computeLongerThanRequest));
  }

  private static Stream<Arguments> invalidConfigurations() {
    return Stream.of(
        Arguments.of("origin.port", "0"),
        Arguments.of("origin.port", "65536"),
        Arguments.of("listen.port", "-1"),
        Arguments.of("listen.host", "bad host"),
        Arguments.of("ingress.trust", "root"),
        Arguments.of("forwarded.trust", "yes"),
        Arguments.of("event-loop.threads", "0"),
        Arguments.of("origin.event-loop.threads", "-1"),
        Arguments.of("frontend.max-connections", "0"),
        Arguments.of("server.backlog", "0"),
        Arguments.of("http.max-initial-line-bytes", "0"),
        Arguments.of("http.max-header-bytes", "x"),
        Arguments.of("http.chunk-bytes", "0"),
        Arguments.of("http.max-request-body-bytes", "-1"),
        Arguments.of("http.chunked-response-threshold-bytes", "-1"),
        Arguments.of("spool.max-files", "0"),
        Arguments.of("origin.max-connections", "0"),
        Arguments.of("origin.max-pending-acquires", "-1"),
        Arguments.of("origin.acquire-timeout", "PT0S"),
        Arguments.of("origin.max-active", "0"),
        Arguments.of("origin.max-queued", "-1"),
        Arguments.of("origin.max-execution-duration", "bad"),
        Arguments.of("origin.max-result-bytes", "0"),
        Arguments.of("origin.connect-timeout", "-"),
        Arguments.of("origin.response-timeout", "PT0S"),
        Arguments.of("origin.failure-cooldown", "PT-1S"),
        Arguments.of("origin.max-cooldown-entries", "0"),
        Arguments.of("request.timeout", "PT0S"),
        Arguments.of("idle.timeout", "PT0S"),
        Arguments.of("drain.timeout", "PT0S"),
        Arguments.of("overload.retry-after-seconds", "0"));
  }

  private static Map<String, String> baseValues() {
    Map<String, String> values = new HashMap<>();
    values.put("origin.host", "127.0.0.1");
    values.put("origin.port", "8080");
    values.put("temporary.directory", Path.of("build", "gateway-test-spool").toString());
    return values;
  }
}
