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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LateProcessingLifecycleTest {
  @TempDir Path temporaryDirectory;

  @Test
  void outcomeCompletingAfterRequestTimeoutIsDiscarded() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ArtifactStore store =
        new ArtifactStore() {
          @Override
          public Optional<Artifact> get(OperationKey key) throws IOException {
            entered.countDown();
            try {
              if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IOException("test store release timed out");
              }
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new IOException("test store interrupted", exception);
            }
            return Optional.empty();
          }

          @Override
          public void put(OperationKey key, Artifact artifact) {}
        };

    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of(
                "origin.max-execution-duration", "PT0.1S",
                "origin.response-timeout", "PT0.1S",
                "request.timeout", "PT0.15S"));

    AtomicReference<RawHttpClient.Response> response = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config, GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()), store)) {
      Thread requester =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
                      response.set(
                          client.request(
                              "GET",
                              "/late",
                              Map.of("Host", "example.test", "Connection", "close"),
                              new byte[0]));
                    } catch (Throwable throwable) {
                      failure.set(throwable);
                    }
                  });

      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTrue(requester.join(Duration.ofSeconds(2)));
      assertEquals(null, failure.get());
      assertEquals(408, response.get().status());

      release.countDown();

      try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        RawHttpClient.Response next =
            client.request(
                "GET", "/next", Map.of("Host", "example.test", "Connection", "close"), new byte[0]);
        assertEquals(404, next.status());
      }
    } finally {
      release.countDown();
    }
  }

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
      try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        RawHttpClient.Response response =
            client.request(
                "GET",
                "/runtime-store-failure",
                Map.of("Host", "example.test", "Connection", "close"),
                new byte[0]);

        assertEquals(500, response.status());
        assertTrue(response.bodyText().contains("gateway processing failed"));
      }
    }
  }
}
