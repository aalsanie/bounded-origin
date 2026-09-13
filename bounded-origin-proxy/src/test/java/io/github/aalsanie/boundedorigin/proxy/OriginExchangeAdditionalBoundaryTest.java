package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginExchangeAdditionalBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void implicitKeepAliveCloseDelimitedResponseCompletesButIsNeverReused() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/implicit-close",
          (request, socket) -> {
            TestOriginServer.write(socket, "HTTP/1.1 200 OK\r\n\r\nvalue");
            return false;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertEquals("value", request(gateway, "GET", "/implicit-close").bodyText());
        assertEquals("value", request(gateway, "GET", "/implicit-close").bodyText());
        assertEquals(2, origin.connections());
      }
    }
  }

  @Test
  void originClosingBeforeAnyResponseReturnsBadGateway() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond("/empty", (request, socket) -> false);

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "GET", "/empty");
        assertEquals(502, response.status());
        assertTrue(response.bodyText().contains("origin request failed"));
      }
    }
  }

  @Test
  void headWithoutRepresentationLengthCompletesWithoutWaitingForConnectionClose() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/head-no-length",
          (request, socket) -> {
            TestOriginServer.write(socket, "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "HEAD", "/head-no-length");
        assertEquals(200, response.status());
        assertEquals("0", response.header("content-length"));
        assertEquals(0, response.body().length);
      }
    }
  }

  private static RawHttpClient.Response request(
      BoundedOriginGateway gateway, String method, String path) throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          method, path, Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
    }
  }
}
