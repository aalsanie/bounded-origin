package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayHandlerMutationContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void successfulKeepAliveResponseRequestsTheNextMessageOnTheSameConnection() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    store.defaultArtifact(GatewayTestFixtures.artifact(200, "ok"));
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config, GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()), store);
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      RawHttpClient.Response first =
          client.request(
              "GET",
              "/one",
              Map.of("Host", "example.test", "Connection", "keep-alive"),
              new byte[0]);
      RawHttpClient.Response second =
          client.request(
              "GET",
              "/two",
              Map.of("Host", "example.test", "Connection", "keep-alive"),
              new byte[0]);

      assertEquals(200, first.status());
      assertEquals(200, second.status());
      assertMetric(gateway, "bounded_origin_requests_total", 2);
      assertMetric(gateway, "bounded_origin_artifact_hits_total", 2);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_client_connections", 1);
    }
  }

  @Test
  void malformedRequestForcesConnectionCloseAndCountsMalformedInput() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
                new GatewayTestFixtures.MemoryArtifactStore());
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      RawHttpClient.Response response =
          client.request(
              "GET",
              "/bad%zz",
              Map.of("Host", "example.test", "Connection", "keep-alive"),
              new byte[0]);

      assertEquals(400, response.status());
      assertTrue(client.awaitClosed(Duration.ofSeconds(1)));
      assertMetric(gateway, "bounded_origin_requests_total", 1);
      assertMetric(gateway, "bounded_origin_malformed_requests_total", 1);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
      assertMetric(gateway, "bounded_origin_client_connections", 0);
    }
  }

  @Test
  void methodNotAllowedIsNotCountedAsMalformedButStillClosesTheConnection() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
            GatewayTestFixtures.start(
                config,
                GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
                new GatewayTestFixtures.MemoryArtifactStore());
        RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      RawHttpClient.Response response =
          client.request(
              "CONNECT",
              "/tunnel",
              Map.of("Host", "example.test", "Connection", "keep-alive"),
              new byte[0]);

      assertEquals(405, response.status());
      assertTrue(client.awaitClosed(Duration.ofSeconds(1)));
      assertMetric(gateway, "bounded_origin_requests_total", 1);
      assertMetric(gateway, "bounded_origin_malformed_requests_total", 0);
      assertMetric(gateway, "bounded_origin_active_requests", 0);
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
