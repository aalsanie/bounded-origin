package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlightLeaseRegistryLifecycleTest {
  @TempDir Path temporaryDirectory;

  @Test
  void completionAfterLastLeaseReleaseDeletesTemporaryArtifact() throws Exception {
    FlightLeaseRegistry registry = new FlightLeaseRegistry(new GatewayMetrics());
    CompletableFuture<Artifact> stage = new CompletableFuture<>();
    FlightLeaseRegistry.Lease lease = registry.acquire(stage);
    TemporaryArtifactBody body = temporaryBody("payload");
    Artifact artifact = new Artifact(7, Map.of(), body);

    lease.close();
    assertEquals(1, registry.trackedFlights());
    assertFalse(body.deleted());

    stage.complete(artifact);

    assertTrue(body.deleted());
    assertEquals(0, registry.trackedFlights());
  }

  @Test
  void completionAfterLastLeaseReleaseDoesNotTreatOrdinaryArtifactAsTemporary() {
    FlightLeaseRegistry registry = new FlightLeaseRegistry(new GatewayMetrics());
    CompletableFuture<Artifact> stage = new CompletableFuture<>();
    FlightLeaseRegistry.Lease lease = registry.acquire(stage);
    Artifact artifact = ResponseArtifacts.text(200, "ok");

    lease.close();
    stage.complete(artifact);

    assertEquals(0, registry.trackedFlights());
  }

  private TemporaryArtifactBody temporaryBody(String value) throws Exception {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    SpoolQuota quota = new SpoolQuota(1024, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "flight-release-", 1024, quota);
    spool.append(bytes).toCompletableFuture().join();
    return new TemporaryArtifactBody(spool.finish().toCompletableFuture().join());
  }
}
