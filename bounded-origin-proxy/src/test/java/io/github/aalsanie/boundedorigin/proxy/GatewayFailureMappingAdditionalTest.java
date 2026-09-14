package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayFailureMappingAdditionalTest {
  @TempDir Path temporaryDirectory;

  @Test
  void unexpectedStoreRuntimeFailureIsContainedAsGatewayFailure() throws Exception {
    ArtifactStore store =
        new ArtifactStore() {
          @Override
          public Optional<Artifact> get(OperationKey key) {
            throw new IllegalStateException("unexpected store failure");
          }

          @Override
          public void put(OperationKey key, Artifact artifact) {}
        };
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config, GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()), store)) {
      RawHttpClient.Response response = request(gateway, "/runtime-store-failure");
      assertEquals(500, response.status());
      assertTrue(response.bodyText().contains("gateway processing failed"));
    }
  }

  @Test
  void executorDeadlineIsMappedSeparatelyFromOriginResponseTimeout() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/execution-timeout",
          (request, socket) -> {
            Thread.sleep(Duration.ofMillis(300));
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.max-execution-duration", "PT0.1S",
                  "origin.response-timeout", "PT0.5S",
                  "request.timeout", "PT0.8S"));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response response = request(gateway, "/execution-timeout");
        assertEquals(504, response.status());
        assertTrue(response.bodyText().contains("origin execution timed out"));
      }
    }
  }

  private static RawHttpClient.Response request(BoundedOriginGateway gateway, String path)
      throws IOException {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(
          "GET", path, Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
    }
  }
}
