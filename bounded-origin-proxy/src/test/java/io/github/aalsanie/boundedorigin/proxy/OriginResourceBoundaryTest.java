package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginResourceBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void resetContentWithZeroLengthIsValidAndConnectionIsReusable() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/reset-content",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 205 Reset Content\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: keep-alive\r\n\r\n");
            return true;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertEquals(205, request(gateway, "GET", "/reset-content", new byte[0]).status());
        assertEquals(205, request(gateway, "GET", "/reset-content", new byte[0]).status());
        assertEquals(1, origin.connections());
      }
    }
  }

  @Test
  void closeDelimitedResponseThatExceedsStreamingLimitReturnsBadGateway() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/large-close-delimited",
          (request, socket) -> {
            TestOriginServer.write(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n12345");
            return false;
          });

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("origin.max-result-bytes", "4"));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response =
            request(gateway, "GET", "/large-close-delimited", new byte[0]);
        assertEquals(502, response.status());
        assertTrue(response.bodyText().contains("exceeds configured limit"));
      }
    }
  }

  @Test
  void originResponseSpoolFileExhaustionReturnsCapacityUnavailable() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/file-capacity", 200, "x");

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("spool.max-files", "1"));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "GET", "/file-capacity", new byte[0]);
        assertEquals(503, response.status());
        assertEquals("1", response.header("retry-after"));
        assertTrue(response.bodyText().contains("capacity unavailable"));
      }
    }
  }

  @Test
  void originResponseSpoolByteExhaustionReturnsCapacityUnavailable() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/byte-capacity", 200, "x");

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("http.max-request-body-bytes", "1"),
                  Map.entry("origin.max-result-bytes", "1"),
                  Map.entry("spool.max-bytes", "1"),
                  Map.entry("spool.max-files", "2")));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response =
            request(gateway, "POST", "/byte-capacity", new byte[] {7});
        assertEquals(503, response.status());
        assertEquals("1", response.header("retry-after"));
        assertTrue(response.bodyText().contains("capacity unavailable"));
      }
    }
  }

  private static RawHttpClient.Response request(
      BoundedOriginGateway gateway, String method, String path, byte[] body) throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          method, path, Map.of("Host", "example.test", "Connection", "close"), body);
    }
  }
}
