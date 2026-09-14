package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlightLeaseRegistryTest {
  @TempDir Path tempDirectory;

  @Test
  void sharedStageDeletesTemporaryArtifactOnlyAfterCompletionAndLastLease() throws Exception {
    GatewayMetrics metrics = new GatewayMetrics();
    FlightLeaseRegistry registry = new FlightLeaseRegistry(metrics);
    CompletableFuture<Artifact> stage = new CompletableFuture<>();
    FlightLeaseRegistry.Lease first = registry.acquire(stage);
    FlightLeaseRegistry.Lease second = registry.acquire(stage);
    StreamingSpool.Result result = temporary("payload");
    TemporaryArtifactBody body = new TemporaryArtifactBody(result);
    Artifact artifact = new Artifact(7, Map.of(), body);

    assertEquals(1, registry.trackedFlights());
    assertEquals(1, metrics.snapshot().singleFlightJoins());

    first.close();
    first.close();
    assertFalse(body.deleted());
    stage.complete(artifact);
    assertFalse(body.deleted());
    second.close();

    assertTrue(body.deleted());
    assertEquals(0, registry.trackedFlights());
  }

  @Test
  void completedFailureNeedsNoTemporaryCleanup() {
    GatewayMetrics metrics = new GatewayMetrics();
    FlightLeaseRegistry registry = new FlightLeaseRegistry(metrics);
    CompletableFuture<Artifact> stage = new CompletableFuture<>();
    FlightLeaseRegistry.Lease lease = registry.acquire(stage);
    stage.completeExceptionally(new IllegalStateException("failed"));
    lease.close();
    assertEquals(0, registry.trackedFlights());
  }

  private StreamingSpool.Result temporary(String value) throws Exception {
    SpoolQuota quota = new SpoolQuota(1024, 1);
    StreamingSpool spool = new StreamingSpool(tempDirectory, "flight-", 1024, quota);
    spool
        .append(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toCompletableFuture()
        .join();
    return spool.finish().toCompletableFuture().join();
  }
}
