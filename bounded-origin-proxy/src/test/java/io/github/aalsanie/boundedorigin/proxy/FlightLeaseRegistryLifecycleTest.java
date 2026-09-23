package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlightLeaseRegistryLifecycleTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"none", "io", "runtime"})
  void ownedImmediateResultsReleaseOnceEvenWhenCleanupFails(String failure) {
    AtomicInteger closes = new AtomicInteger();
    Artifact artifact =
        new Artifact(
            0,
            Map.of(),
            new ArtifactBody() {
              @Override
              public InputStream openStream() {
                return InputStream.nullInputStream();
              }

              @Override
              public void close() throws IOException {
                closes.incrementAndGet();
                if (failure.equals("io")) {
                  throw new IOException("cleanup");
                }
                if (failure.equals("runtime")) {
                  throw new IllegalStateException("cleanup");
                }
              }
            });
    FlightLeaseRegistry registry = new FlightLeaseRegistry(new GatewayMetrics());
    var lease = registry.acquire(artifact);
    assertEquals(artifact, lease.result().toCompletableFuture().join());
    assertEquals(1, registry.trackedFlights());
    lease.close();
    lease.close();
    assertEquals(1, closes.get());
    assertEquals(0, registry.trackedFlights());
  }

  @Test
  void completionAfterLastLeaseReleaseDeletesTemporaryArtifact() throws Exception {
    FlightLeaseRegistry registry = new FlightLeaseRegistry(new GatewayMetrics());
    SharedExecutionFixture fixture = new SharedExecutionFixture();
    var execution = fixture.join();
    FlightLeaseRegistry.Lease lease = registry.acquire(execution);
    TemporaryArtifactBody body = temporaryBody("payload");
    Artifact artifact = new Artifact(7, Map.of(), body);

    lease.close();
    assertEquals(1, registry.trackedFlights());
    assertFalse(body.deleted());

    fixture.produced.complete(artifact);
    execution.result().toCompletableFuture().join();

    assertTrue(body.deleted());
    SharedExecutionFixture.awaitUntracked(registry);
    fixture.close();
  }

  @Test
  void completionAfterLastLeaseReleaseDoesNotTreatOrdinaryArtifactAsTemporary() throws Exception {
    FlightLeaseRegistry registry = new FlightLeaseRegistry(new GatewayMetrics());
    SharedExecutionFixture fixture = new SharedExecutionFixture();
    var execution = fixture.join();
    FlightLeaseRegistry.Lease lease = registry.acquire(execution);
    Artifact artifact = ResponseArtifacts.text(200, "ok");

    lease.close();
    fixture.produced.complete(artifact);
    execution.result().toCompletableFuture().join();

    SharedExecutionFixture.awaitUntracked(registry);
    fixture.close();
  }

  private TemporaryArtifactBody temporaryBody(String value) throws Exception {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    SpoolQuota quota = new SpoolQuota(1024, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "flight-release-", 1024, quota);
    spool.append(bytes).toCompletableFuture().join();
    return new TemporaryArtifactBody(spool.finish().toCompletableFuture().join());
  }
}
