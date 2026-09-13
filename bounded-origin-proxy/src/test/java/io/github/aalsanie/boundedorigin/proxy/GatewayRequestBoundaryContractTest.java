package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayRequestBoundaryContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void exactDeclaredLengthIsAcceptedAndPreservesKeepAlive() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.defaultArtifact(GatewayTestFixtures.artifact(200, "ok"));
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
                store);
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      RawHttpClient.Response first =
          client.request(
              "POST",
              "/exact",
              Map.of(
                  "Host",
                  "example.test",
                  "Connection",
                  "keep-alive",
                  "Content-Length",
                  "1"),
              new byte[] {7});
      RawHttpClient.Response second =
          client.request(
              "GET",
              "/next",
              Map.of("Host", "example.test", "Connection", "keep-alive"),
              new byte[0]);

      assertEquals(200, first.status());
      assertEquals("ok", first.bodyText());
      assertEquals(200, second.status());
      assertMetric(gateway, "bounded_origin_requests_total", 2);
      assertMetric(gateway, "bounded_origin_request_body_bytes_total", 1);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_client_connections", 1);
    }
  }

  @Test
  void chunkedBodyAcceptsExactLimitAndRejectsOneByteOver() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.defaultArtifact(GatewayTestFixtures.artifact(200, "ok"));
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of("http.max-request-body-bytes", "3"));

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
            store)) {
      try (RawHttpClient exact = new RawHttpClient(gateway.listenAddress())) {
        exact.write(
            "POST /exact-chunk HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Connection: keep-alive\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "3\r\nabc\r\n0\r\n\r\n");
        RawHttpClient.Response response = exact.readResponse("POST");
        assertEquals(200, response.status());
        assertEquals("ok", response.bodyText());
      }

      try (RawHttpClient over = new RawHttpClient(gateway.listenAddress())) {
        over.write(
            "POST /over-chunk HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Connection: keep-alive\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n"
                + "4\r\nabcd\r\n0\r\n\r\n");
        RawHttpClient.Response response = over.readResponse("POST");
        assertEquals(413, response.status());
        assertTrue(over.awaitClosed(Duration.ofSeconds(1)));
      }

      assertMetric(gateway, "bounded_origin_request_body_bytes_total", 3);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
    }
  }

  @Test
  void spoolCapacityFailureReturnsRetryableOverloadAndReleasesReservation() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.defaultArtifact(GatewayTestFixtures.artifact(200, "ok"));
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of(
                "http.max-request-body-bytes", "16",
                "origin.max-result-bytes", "16",
                "spool.max-bytes", "16"));

    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
                store);
        RawHttpClient holder = new RawHttpClient(gateway.listenAddress());
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      holder.write(
          "POST /holder HTTP/1.1\r\n"
              + "Host: example.test\r\n"
              + "Connection: keep-alive\r\n"
              + "Content-Length: 16\r\n\r\n"
              + "123456789012345");
      assertMetric(gateway, "bounded_origin_spool_bytes", 15);
      assertMetric(gateway, "bounded_origin_spool_files", 1);

      client.write(
          "POST /capacity HTTP/1.1\r\n"
              + "Host: example.test\r\n"
              + "Connection: keep-alive\r\n"
              + "Transfer-Encoding: chunked\r\n\r\n"
              + "2\r\nab\r\n0\r\n\r\n");
      RawHttpClient.Response response = client.readResponse("POST");

      assertEquals(503, response.status());
      assertEquals("1", response.header("retry-after"));
      assertTrue(client.awaitClosed(Duration.ofSeconds(1)));
      assertMetric(gateway, "bounded_origin_rejections_total", 1);

      holder.close();
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
    }
  }

  @Test
  void incompleteRequestTimesOutOnceAndReleasesAllRequestState() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.defaultArtifact(GatewayTestFixtures.artifact(200, "ok"));
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of(
                "origin.max-execution-duration",
                "PT0.15S",
                "origin.response-timeout",
                "PT0.1S",
                "request.timeout",
                "PT0.2S"));

    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
                store);
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      client.write(
          "POST /timeout HTTP/1.1\r\n"
              + "Host: example.test\r\n"
              + "Connection: keep-alive\r\n"
              + "Content-Length: 1\r\n\r\n");
      RawHttpClient.Response response = client.readResponse("POST");

      assertEquals(408, response.status());
      assertTrue(client.awaitClosed(Duration.ofSeconds(1)));
      assertMetric(gateway, "bounded_origin_rejections_total", 1);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_spool_bytes", 0);
      assertMetric(gateway, "bounded_origin_spool_files", 0);
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
}