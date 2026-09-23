package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RepresentationStorageTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void validatesEveryStoreReadIncludingPublicationRereadAndClosesRejectedBodies(
      boolean reread, boolean closeFails) throws Exception {
    AtomicInteger puts = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    AtomicInteger reads = new AtomicInteger();
    Artifact invalid =
        new Artifact(
            200,
            6,
            Map.of("cache-control", "private, no-store"),
            new ArtifactBody() {
              @Override
              public InputStream openStream() {
                throw new AssertionError("private stored body must never be opened");
              }

              @Override
              public void close() throws IOException {
                closes.incrementAndGet();
                if (closeFails) {
                  throw new IOException("synthetic cleanup failure");
                }
              }
            });
    ArtifactStore store =
        new ArtifactStore() {
          @Override
          public Optional<Artifact> get(OperationKey key) {
            int count = reads.incrementAndGet();
            return reread && count == 1 ? Optional.empty() : Optional.of(invalid);
          }

          @Override
          public void put(OperationKey key, Artifact artifact) throws IOException {
            try (InputStream input = artifact.body().openStream()) {
              assertEquals(
                  "public",
                  new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            puts.incrementAndGet();
          }
        };
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/stored", 200, "public");
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), directory);
      var policy =
          reread
              ? GatewayTestFixtures.materializePolicy(config.globalBudget())
              : GatewayTestFixtures.artifactOnlyPolicy();
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(config, GatewayTestFixtures.engine(policy), store);
          RawHttpClient caller = new RawHttpClient(gateway.listenAddress())) {
        var response =
            caller.request("GET", "/stored", Map.of("Host", "example.test"), new byte[0]);
        assertEquals(500, response.status());
        assertFalse(response.bodyText().contains("SECRET"));
        assertEquals(1, closes.get());
        assertEquals(reread ? 2 : 1, reads.get());
        assertEquals(reread ? 1 : 0, puts.get());
        assertEquals(reread ? 1 : 0, origin.requests());
      }
    }
  }
}
