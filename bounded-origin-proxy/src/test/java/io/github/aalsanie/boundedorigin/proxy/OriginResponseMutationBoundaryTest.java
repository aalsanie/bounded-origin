package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginResponseMutationBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void statusCode599IsAcceptedAnd600IsRejected() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/599",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 599 Edge\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      origin.respond(
          "/600",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 600 Invalid\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });

      try (BoundedOriginGateway gateway = gateway(origin, Map.of())) {
        assertEquals(599, request(gateway, "/599").status());
        RawHttpClient.Response rejected = request(gateway, "/600");
        assertEquals(502, rejected.status());
        assertTrue(rejected.bodyText().contains("origin request failed"));
      }
    }
  }

  @Test
  void originResponseLimitAcceptsExactBoundaryAndRejectsOneByteOver() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/exact",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      origin.respond(
          "/over",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nbad");
            return true;
          });

      try (BoundedOriginGateway gateway =
          gateway(origin, Map.of("origin.max-result-bytes", "2"))) {
        RawHttpClient.Response exact = request(gateway, "/exact");
        assertEquals(200, exact.status());
        assertEquals("ok", exact.bodyText());

        RawHttpClient.Response over = request(gateway, "/over");
        assertEquals(502, over.status());
        assertTrue(over.bodyText().contains("exceeds configured limit"));
      }
    }
  }

  @Test
  void bodyForbiddenResponsesDistinguishZeroLengthFromDeclaredContent() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/empty",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      origin.respond(
          "/invalid",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 204 No Content\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\nx");
            return true;
          });

      try (BoundedOriginGateway gateway = gateway(origin, Map.of())) {
        assertEquals(204, request(gateway, "/empty").status());
        RawHttpClient.Response invalid = request(gateway, "/invalid");
        assertEquals(502, invalid.status());
        assertTrue(invalid.bodyText().contains("origin request failed"));
      }
    }
  }

  private BoundedOriginGateway gateway(TestOriginServer origin, Map<String, String> overrides)
      throws Exception {
    GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory, overrides);
    return GatewayTestFixtures.start(
        config,
        GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
        new GatewayTestFixtures.MemoryArtifactStore());
  }

  private static RawHttpClient.Response request(BoundedOriginGateway gateway, String path)
      throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          "GET", path, Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
    }
  }
}
