package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginProtocolValidationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void invalidUpgradeStatusAndBodyForbiddenFramingAreRejected() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/upgrade",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Connection: upgrade\r\n"
                    + "Upgrade: websocket\r\n\r\n");
            return false;
          });
      origin.respond(
          "/invalid-status",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 600 Invalid\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            return false;
          });
      origin.respond(
          "/no-content-body",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 204 No Content\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      origin.respond(
          "/reset-content-body",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 205 Reset Content\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertOriginFailure(gateway, "/upgrade");
        assertOriginFailure(gateway, "/invalid-status");
        assertOriginFailure(gateway, "/no-content-body");
        assertOriginFailure(gateway, "/reset-content-body");
      }
    }
  }

  @Test
  void malformedOriginLengthAndChunkFramingAreRejected() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/bad-length",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: nope\r\nConnection: close\r\n\r\n");
            return false;
          });
      origin.respond(
          "/bad-chunk",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    + "zz\r\n");
            return false;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertOriginFailure(gateway, "/bad-length");
        assertOriginFailure(gateway, "/bad-chunk");
      }
    }
  }

  private static void assertOriginFailure(BoundedOriginGateway gateway, String path)
      throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      RawHttpClient.Response response =
          client.request(
              "GET", path, Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
      assertEquals(502, response.status());
      assertTrue(response.bodyText().contains("origin"));
    }
  }
}
