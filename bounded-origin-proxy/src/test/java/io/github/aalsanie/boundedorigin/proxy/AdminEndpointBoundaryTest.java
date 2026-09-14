package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminEndpointBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void adminRejectsBodiesAndDecoderRejectedFraming() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      assertEquals(
          400,
          raw(
                  gateway,
                  "GET /health HTTP/1.1\r\n"
                      + "Host: admin\r\n"
                      + "Content-Length: 1\r\n\r\n"
                      + "x")
              .status());
      assertEquals(
          400,
          raw(
                  gateway,
                  "GET /health HTTP/1.1\r\n" + "Host: admin\r\n" + "Content-Length: nope\r\n\r\n")
              .status());
      assertEquals(
          400,
          raw(
                  gateway,
                  "GET /health HTTP/1.1\r\n"
                      + "Host: admin\r\n"
                      + "Content-Length: 0\r\n"
                      + "Content-Length: 0\r\n\r\n")
              .status());
      assertEquals(
          200,
          raw(gateway, "GET /health HTTP/1.1\r\nHost: admin\r\nContent-Length: 0\r\n\r\n")
              .status());
    }
  }

  private static RawHttpClient.Response raw(BoundedOriginGateway gateway, String request)
      throws Exception {
    try (RawHttpClient client = new RawHttpClient(gateway.adminAddress())) {
      client.write(request);
      return client.readResponse("GET");
    }
  }
}
