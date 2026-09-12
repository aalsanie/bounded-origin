package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyCapacityIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void policyQueueLimitRejectsUniqueWorkWhileGlobalCapacityRemains() throws Exception {
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
                  Map.entry("origin.max-active", "2"),
                  Map.entry("origin.max-queued", "2"),
                  Map.entry("origin.max-connections", "2"),
                  Map.entry("origin.max-pending-acquires", "2"),
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S")));

      Budget policyBudget =
          new Budget(1, 0, config.globalBudget().timeout(), config.globalBudget().maxResultBytes());
      OriginPolicy policy =
          OriginPolicy.boundedCompute(
              "bounded",
              1,
              100,
              "origin-v1",
              Canonicalizers.byDimensions(
                  List.of("method", "host", "path", "query", "body-sha256")),
              policyBudget);

      AtomicReference<RawHttpClient.Response> firstResponse = new AtomicReference<>();
      AtomicReference<Throwable> firstFailure = new AtomicReference<>();

      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(policy),
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
      throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          "GET", path, Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
    }
  }
}
