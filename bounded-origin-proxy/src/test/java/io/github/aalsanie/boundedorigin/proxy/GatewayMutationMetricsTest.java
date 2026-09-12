package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayMutationMetricsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void artifactOnlyHitAccountsForTheRequestAndReleasesItsBody() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.defaultArtifact(GatewayTestFixtures.artifact(201, "cached"));
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config, GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()), store)) {
      RawHttpClient.Response response = request(gateway, "POST", "/cached", "abc");

      assertEquals(201, response.status());
      assertEquals("cached", response.bodyText());
      assertMetric(gateway, "bounded_origin_requests_total", 1);
      assertMetric(gateway, "bounded_origin_artifact_hits_total", 1);
      assertMetric(gateway, "bounded_origin_request_body_bytes_total", 3);
      assertMetric(gateway, "bounded_origin_bytes_served_total", 6);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_client_connections", 0);
    }
  }

  @Test
  void artifactOnlyMissAndPolicyDenialReleaseRequestBodies() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      assertEquals(404, request(gateway, "POST", "/missing", "miss").status());
      assertMetric(gateway, "bounded_origin_artifact_misses_total", 1);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
    }

    GatewayConfig deniedConfig =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(), temporaryDirectory.resolve("deny"));
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            deniedConfig,
            GatewayTestFixtures.routeEngine(
                Map.of("/allowed", GatewayTestFixtures.artifactOnlyPolicy())),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      assertEquals(403, request(gateway, "POST", "/denied", "no").status());
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
    }
  }

  @Test
  void clientComputeDecisionIsCountedAndDoesNotRetainTheRequestBody() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.clientComputePolicy()),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      RawHttpClient.Response response = request(gateway, "POST", "/client", "work");

      assertEquals(200, response.status());
      assertTrue(response.bodyText().contains("sha256"));
      assertMetric(gateway, "bounded_origin_client_compute_decisions_total", 1);
      assertMetric(gateway, "bounded_origin_request_body_bytes_total", 4);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
    }
  }

  @Test
  void boundedComputeCountsOriginWorkAndReleasesFlightAndSpools() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/bounded",
          (request, socket) -> {
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), request.body());
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);

      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "POST", "/bounded", "abc");

        assertEquals(200, response.status());
        assertEquals("ok", response.bodyText());
        assertEquals(1, origin.requests());
        awaitFlights(gateway, 0);
        assertMetric(gateway, "bounded_origin_artifact_misses_total", 1);
        assertMetric(gateway, "bounded_origin_origin_executions_total", 1);
        assertMetric(gateway, "bounded_origin_origin_duration_seconds_count", 1);
        assertMetric(gateway, "bounded_origin_origin_response_bytes_total", 2);
        assertMetric(gateway, "bounded_origin_request_body_bytes_total", 3);
        assertMetric(gateway, "bounded_origin_spool_bytes", 0);
        assertMetric(gateway, "bounded_origin_spool_files", 0);
        assertMetric(gateway, "bounded_origin_origin_in_flight", 0);
      }
    }
  }

  @Test
  void materializationAccountsForStorageAndTheSecondRequestIsAnArtifactHit() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/materialize",
          (request, socket) -> {
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), request.body());
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();

      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(
                  GatewayTestFixtures.materializePolicy(config.globalBudget())),
              store)) {
        assertEquals(200, request(gateway, "POST", "/materialize", "abc").status());
        assertEquals(200, request(gateway, "POST", "/materialize", "abc").status());

        assertEquals(1, origin.requests());
        assertEquals(1, store.size());
        awaitFlights(gateway, 0);
        assertMetric(gateway, "bounded_origin_artifact_misses_total", 1);
        assertMetric(gateway, "bounded_origin_artifact_hits_total", 1);
        assertMetric(gateway, "bounded_origin_origin_executions_total", 1);
        assertMetric(gateway, "bounded_origin_bytes_stored_total", 2);
        assertMetric(gateway, "bounded_origin_materialization_duration_seconds_count", 1);
        assertMetric(gateway, "bounded_origin_origin_response_bytes_total", 2);
        assertMetric(gateway, "bounded_origin_spool_bytes", 0);
        assertMetric(gateway, "bounded_origin_spool_files", 0);
      }
    }
  }

  @Test
  void storeReadFailureIsCountedAndStillReleasesTheRequestBody() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.failReads(true);
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config, GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()), store)) {
      RawHttpClient.Response response = request(gateway, "POST", "/store-failure", "x");

      assertEquals(500, response.status());
      assertMetric(gateway, "bounded_origin_store_failures_total", 1);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
    }
  }

  private static RawHttpClient.Response request(
      BoundedOriginGateway gateway, String method, String path, String body) throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          method,
          path,
          Map.of("Host", "example.test", "Connection", "close"),
          body.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void assertMetric(BoundedOriginGateway gateway, String name, long expected)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    long actual = Long.MIN_VALUE;
    while (System.nanoTime() - deadline < 0) {
      try (RawHttpClient admin = new RawHttpClient(gateway.adminAddress())) {
        RawHttpClient.Response response =
            admin.request("GET", "/metrics", Map.of("Host", "admin"), new byte[0]);
        actual = metric(response.bodyText(), name);
        if (actual == expected) {
          return;
        }
      }
      Thread.sleep(5);
    }
    assertEquals(expected, actual, name);
  }

  private static long metric(String prometheus, String name) {
    String prefix = name + " ";
    for (String line : prometheus.lines().toList()) {
      if (line.startsWith(prefix)) {
        return Long.parseLong(line.substring(prefix.length()));
      }
    }
    throw new AssertionError("missing metric " + name);
  }

  private static void awaitFlights(BoundedOriginGateway gateway, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (gateway.trackedFlights() != expected && System.nanoTime() - deadline < 0) {
      Thread.sleep(5);
    }
    assertEquals(expected, gateway.trackedFlights());
  }
}
