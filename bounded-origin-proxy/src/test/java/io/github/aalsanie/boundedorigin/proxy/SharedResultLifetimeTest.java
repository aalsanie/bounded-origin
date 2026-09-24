package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharedResultLifetimeTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void joinedCallerOwnsResultBeforeGatewayTracking(boolean persisted) throws Exception {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(30), 1024);
    var canonicalizer = Canonicalizers.byDimensions();
    var policy =
        persisted
            ? OriginPolicy.materialize("p", 1, 1, "v1", canonicalizer, budget)
            : OriginPolicy.boundedCompute("p", 1, 1, "v1", canonicalizer, budget);
    var operation = new Operation("test", Map.of());
    var decision =
        new OriginDecision.Selected(
            policy,
            operation,
            new OperationKey("p", 1, canonicalizer.canonicalize(operation), "v1"));
    var quota = new SpoolQuota(1024, 1);
    var spool = new StreamingSpool(root, "shared-", 1024, quota);
    spool.append("payload".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().join();
    var body = new TemporaryArtifactBody(spool.finish().toCompletableFuture().join());
    var artifact = new Artifact(7, Map.of(), body);
    var metrics = new GatewayMetrics();
    var registry = new FlightLeaseRegistry(metrics);
    var entered = new CountDownLatch(1);
    var complete = new CountDownLatch(1);
    try (var store = new FileSystemArtifactStore(root.resolve("store"), 300, 200);
        var executor = new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 4)) {
      Artifact produced;
      if (persisted) {
        store.put(decision.operationKey(), artifact);
        produced = store.get(decision.operationKey()).orElseThrow();
      } else {
        produced = artifact;
      }
      var first =
          executor.execute(
              decision,
              ignored -> {
                entered.countDown();
                try {
                  assertTrue(complete.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(exception);
                }
                return produced;
              });
      var leader = registry.acquire(first);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var second =
          executor.execute(
              decision,
              ignored -> {
                throw new AssertionError("joined work must not execute again");
              });
      assertSame(first.result(), second.result());
      complete.countDown();
      assertSame(produced, first.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
      leader.close();
      SharedExecutionFixture.awaitUntracked(registry);
      org.junit.jupiter.api.Assertions.assertFalse(body.deleted());
      OperationKey other = new OperationKey("p", 1, "another", "v1");
      Artifact pressure =
          new Artifact(150, Map.of(), () -> new java.io.ByteArrayInputStream(new byte[150]));
      if (persisted) {
        assertThrows(IOException.class, () -> store.put(other, pressure));
      }
      var follower = registry.acquire(second);
      assertEquals(1, metrics.snapshot().singleFlightJoins());
      try (var stream = second.result().toCompletableFuture().join().body().openStream()) {
        assertEquals("payload", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      } finally {
        follower.close();
      }
      if (persisted) {
        store.put(other, pressure);
        assertTrue(store.get(decision.operationKey()).isEmpty());
      } else {
        assertTrue(body.deleted());
      }
      assertEquals(0, registry.trackedFlights());
    } finally {
      complete.countDown();
      body.close();
      quota.close();
    }
  }
}
