package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginResponseSemanticsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void headPreservesRepresentationLengthWithoutServingBody() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/head",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      try (BoundedOriginGateway gateway = gateway(origin)) {
        try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
          RawHttpClient.Response response =
              client.request(
                  "HEAD",
                  "/head",
                  Map.of("Host", "example.test", "Connection", "close"),
                  new byte[0]);
          assertEquals(200, response.status());
          assertEquals("9", response.header("content-length"));
          assertEquals(0, response.body().length);
        }
      }
    }
  }

  @Test
  void notModifiedPreservesRepresentationLengthWithoutBodyFraming() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/not-modified",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 304 Not Modified\r\nContent-Length: 17\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      try (BoundedOriginGateway gateway = gateway(origin)) {
        try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
          RawHttpClient.Response response =
              client.request(
                  "GET",
                  "/not-modified",
                  Map.of("Host", "example.test", "Connection", "close"),
                  new byte[0]);
          assertEquals(304, response.status());
          assertEquals("17", response.header("content-length"));
          assertEquals(0, response.body().length);
        }
      }
    }
  }

  @Test
  void informationalContinueCanPrecedeFinalResponse() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/informational",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 100 Continue\r\n\r\n"
                    + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      try (BoundedOriginGateway gateway = gateway(origin)) {
        RawHttpClient.Response response = request(gateway, "/informational");
        assertEquals(200, response.status());
        assertEquals("ok", response.bodyText());
      }
    }
  }

  @Test
  void closeDelimitedOriginResponseIsAcceptedButNotReused() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/close-delimited",
          (request, socket) -> {
            TestOriginServer.write(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nvalue");
            return false;
          });
      try (BoundedOriginGateway gateway = gateway(origin)) {
        assertEquals("value", request(gateway, "/close-delimited").bodyText());
        assertEquals("value", request(gateway, "/close-delimited").bodyText());
        assertEquals(2, origin.connections());
      }
    }
  }

  @Test
  void chunkedOriginResponseIsSelfDelimitedAndConnectionCanBeReused() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/chunked",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: keep-alive\r\n\r\n"
                    + "5\r\nvalue\r\n"
                    + "0\r\n\r\n");
            return true;
          });
      try (BoundedOriginGateway gateway = gateway(origin)) {
        assertEquals("value", request(gateway, "/chunked").bodyText());
        assertEquals("value", request(gateway, "/chunked").bodyText());
        assertEquals(1, origin.connections());
      }
    }
  }

  @Test
  void originClosingBeforeResponseHeadersReturnsBadGateway() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond("/close-first", (request, socket) -> false);
      try (BoundedOriginGateway gateway = gateway(origin)) {
        RawHttpClient.Response response = request(gateway, "/close-first");
        assertEquals(502, response.status());
        assertTrue(response.bodyText().contains("origin request failed"));
      }
    }
  }

  @Test
  void noContentResponseRemovesRepresentationFraming() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/empty",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 204 No Content\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      try (BoundedOriginGateway gateway = gateway(origin)) {
        RawHttpClient.Response response = request(gateway, "/empty");
        assertEquals(204, response.status());
        assertNull(response.header("content-length"));
        assertNull(response.header("transfer-encoding"));
      }
    }
  }

  private BoundedOriginGateway gateway(TestOriginServer origin) throws Exception {
    GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
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
