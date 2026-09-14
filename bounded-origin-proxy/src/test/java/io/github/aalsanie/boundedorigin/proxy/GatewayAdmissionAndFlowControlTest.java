package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayAdmissionAndFlowControlTest {
  @TempDir Path temporaryDirectory;

  @Test
  void connectionLimitRejectsConnectionsBeforeTheyCanSubmitWork() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of("frontend.max-connections", "1"));
    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(
                    GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                new GatewayTestFixtures.MemoryArtifactStore());
        RawHttpClient first = new RawHttpClient(gateway.listenAddress())) {
      awaitMetric(
          gateway.adminAddress(), "bounded_origin_client_connections", 1, Duration.ofSeconds(1));
      assertFalse(first.awaitClosed(Duration.ofMillis(50)));
      try (RawHttpClient second = new RawHttpClient(gateway.listenAddress())) {
        assertTrue(second.awaitClosed(Duration.ofSeconds(1)));
      }
      assertEquals(1, metric(gateway.adminAddress(), "bounded_origin_client_connections"));
      assertEquals(1, metric(gateway.adminAddress(), "bounded_origin_rejections_total"));
    }
  }

  @Test
  void activeUploadIsGovernedByRequestTimeoutRatherThanIdleConnectionTimeout() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of(
                "idle.timeout", "PT0.05S",
                "origin.max-execution-duration", "PT0.2S",
                "origin.response-timeout", "PT0.2S",
                "request.timeout", "PT0.25S"));
    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(
                    GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                new GatewayTestFixtures.MemoryArtifactStore());
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      client.write(
          "POST /slow-upload HTTP/1.1\r\n"
              + "Host: example.test\r\n"
              + "Content-Length: 1\r\n"
              + "Connection: keep-alive\r\n\r\n");

      Thread.sleep(100);
      RawHttpClient.Response response = client.readResponse("POST");
      assertEquals(408, response.status());
      assertTrue(response.bodyText().contains("timed out"));
    }
  }

  @Test
  void requestSpoolFileLimitRejectsConcurrentBufferedRequest() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of("spool.max-files", "1", "request.timeout", "PT2S"));
    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(
                    GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                new GatewayTestFixtures.MemoryArtifactStore());
        RawHttpClient held = new RawHttpClient(gateway.listenAddress());
        RawHttpClient rejected = new RawHttpClient(gateway.listenAddress())) {
      held.write(
          "POST /held HTTP/1.1\r\n"
              + "Host: example.test\r\n"
              + "Content-Length: 1\r\n"
              + "Connection: keep-alive\r\n\r\n");
      awaitMetric(gateway.adminAddress(), "bounded_origin_spool_files", 1, Duration.ofSeconds(1));

      RawHttpClient.Response response =
          rejected.request(
              "GET",
              "/blocked",
              Map.of("Host", "example.test", "Connection", "close"),
              new byte[0]);
      assertEquals(503, response.status());
      assertEquals("1", response.header("retry-after"));
    }
  }

  @Test
  void requestSpoolByteLimitIsGlobalAcrossConcurrentRequests() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.ofEntries(
                Map.entry("http.max-request-body-bytes", "5"),
                Map.entry("origin.max-result-bytes", "5"),
                Map.entry("spool.max-bytes", "5"),
                Map.entry("spool.max-files", "2"),
                Map.entry("request.timeout", "PT2S")));
    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(
                    GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                new GatewayTestFixtures.MemoryArtifactStore());
        RawHttpClient held = new RawHttpClient(gateway.listenAddress());
        RawHttpClient rejected = new RawHttpClient(gateway.listenAddress())) {
      held.write(
          "POST /held HTTP/1.1\r\n"
              + "Host: example.test\r\n"
              + "Content-Length: 5\r\n"
              + "Connection: keep-alive\r\n\r\n"
              + "1234");
      awaitMetric(gateway.adminAddress(), "bounded_origin_spool_bytes", 4, Duration.ofSeconds(1));

      RawHttpClient.Response response =
          rejected.request(
              "POST",
              "/blocked",
              Map.of("Host", "example.test", "Connection", "close"),
              new byte[] {8, 9});
      assertEquals(503, response.status());
      assertEquals("1", response.header("retry-after"));
    }
  }

  @Test
  void pipelinedRequestIsRejectedWhilePreviousRequestIsStillActive() throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/slow",
          (request, socket) -> {
            originEntered.countDown();
            assertTrue(TestOriginServer.await(releaseOrigin, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.max-execution-duration", "PT4S",
                  "origin.response-timeout", "PT4S",
                  "request.timeout", "PT5S"));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        client.write(
            "GET /slow HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: keep-alive\r\n\r\n");
        assertTrue(originEntered.await(2, TimeUnit.SECONDS));

        client.write(
            "GET /second HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: keep-alive\r\n\r\n");
        assertTrue(client.awaitClosed(Duration.ofSeconds(1)));
        releaseOrigin.countDown();
        assertEquals(1, origin.requests());
      } finally {
        releaseOrigin.countDown();
      }
    }
  }

  private static long metric(java.net.InetSocketAddress address, String name) throws Exception {
    try (RawHttpClient admin = new RawHttpClient(address)) {
      RawHttpClient.Response response =
          admin.request("GET", "/metrics", Map.of("Host", "admin"), new byte[0]);
      assertEquals(200, response.status());
      String prefix = name + " ";
      for (String line : response.bodyText().lines().toList()) {
        if (line.startsWith(prefix)) {
          return Long.parseLong(line.substring(prefix.length()));
        }
      }
      throw new AssertionError("missing metric " + name);
    }
  }

  private static void awaitMetric(
      java.net.InetSocketAddress address, String name, long expected, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    long actual = Long.MIN_VALUE;
    while (System.nanoTime() - deadline < 0) {
      actual = metric(address, name);
      if (actual == expected) {
        return;
      }
      Thread.sleep(5);
    }
    assertEquals(expected, actual);
  }
}
