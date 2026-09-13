package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayFailureMappingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void artifactStoreReadFailureReturnsInternalServerError() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.failReads(true);
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config, GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()), store)) {
      RawHttpClient.Response response = request(gateway, "/read-failure");
      assertEquals(500, response.status());
      assertTrue(response.bodyText().contains("artifact store unavailable"));
    }
  }

  @Test
  void materializationStoreWriteFailureReturnsInternalServerError() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.failWrites(true);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/persist", 200, "value");
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(
                  GatewayTestFixtures.materializePolicy(config.globalBudget())),
              store)) {
        RawHttpClient.Response response = request(gateway, "/persist");
        assertEquals(500, response.status());
        assertTrue(response.bodyText().contains("artifact store unavailable"));
      }
    }
  }

  @Test
  void oversizedOriginResponseReturnsBadGateway() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/large",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\n12345");
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-result-bytes", "4"),
                  Map.entry("spool.max-bytes", "1048576")));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "/large");
        assertEquals(502, response.status());
        assertTrue(response.bodyText().contains("exceeds configured limit"));
      }
    }
  }

  @Test
  void originProtocolFailureEntersCooldownForSameOperationKey() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/broken",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nx");
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.failure-cooldown", "PT2S",
                  "origin.response-timeout", "PT1S",
                  "request.timeout", "PT2S"));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertEquals(502, request(gateway, "/broken").status());
        RawHttpClient.Response cooled = request(gateway, "/broken");
        assertEquals(503, cooled.status());
        assertEquals("1", cooled.header("retry-after"));
        assertEquals(1, origin.requests());
      }
    }
  }

  @Test
  void responseTimeoutReturnsGatewayTimeout() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/timeout",
          (request, socket) -> {
            Thread.sleep(Duration.ofMillis(400));
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.max-execution-duration", "PT0.3S",
                  "origin.response-timeout", "PT0.1S",
                  "request.timeout", "PT0.5S"));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "/timeout");
        assertEquals(504, response.status());
        assertTrue(response.bodyText().contains("timed out"));
      }
    }
  }

  @Test
  void refusedOriginConnectionReturnsBadGateway() throws Exception {
    int unusedPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config =
        GatewayTestFixtures.config(
            unusedPort,
            temporaryDirectory,
            Map.of(
                "origin.connect-timeout", "PT0.2S",
                "origin.response-timeout", "PT0.5S",
                "origin.max-execution-duration", "PT0.8S",
                "request.timeout", "PT1S"));
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      RawHttpClient.Response response = request(gateway, "/unreachable");
      assertEquals(502, response.status());
      assertTrue(response.bodyText().contains("origin request failed"));
    }
  }

  @Test
  void exhaustedGlobalQueueReturnsServiceUnavailableWithoutSecondOriginExecution()
      throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/slow",
          (request, socket) -> {
            firstEntered.countDown();
            assertTrue(TestOriginServer.await(releaseFirst, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      origin.fixed("/second", 200, "second");
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-active", "1"),
                  Map.entry("origin.max-queued", "0"),
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "0"),
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S")));
      AtomicReference<RawHttpClient.Response> firstResponse = new AtomicReference<>();
      AtomicReference<Throwable> firstFailure = new AtomicReference<>();
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        Thread first =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        firstResponse.set(request(gateway, "/slow"));
                      } catch (Throwable throwable) {
                        firstFailure.set(throwable);
                      }
                    });
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        RawHttpClient.Response rejected = request(gateway, "/second");
        assertEquals(503, rejected.status());
        assertEquals("1", rejected.header("retry-after"));
        assertEquals(1, origin.requests());

        releaseFirst.countDown();
        assertTrue(first.join(Duration.ofSeconds(2)));
        assertNull(firstFailure.get());
        assertEquals(200, firstResponse.get().status());
      } finally {
        releaseFirst.countDown();
      }
    }
  }

  private static RawHttpClient.Response request(BoundedOriginGateway gateway, String path)
      throws IOException {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          "GET", path, Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
    }
  }
}
