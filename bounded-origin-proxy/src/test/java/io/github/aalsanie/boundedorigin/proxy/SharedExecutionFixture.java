package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.OriginExecution;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class SharedExecutionFixture implements AutoCloseable {
  private final Budget budget = new Budget(1, 0, Duration.ofSeconds(30), 1024);
  private final BoundedOriginExecutor executor =
      new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 4);
  private final OriginPolicy policy =
      OriginPolicy.boundedCompute("p", 1, 1, "v1", Canonicalizers.byDimensions(), budget);
  private final OriginDecision.Selected decision =
      new OriginDecision.Selected(
          policy, new Operation("test", Map.of()), new OperationKey("p", 1, "one", "v1"));
  private final CountDownLatch entered = new CountDownLatch(1);
  final CompletableFuture<Artifact> produced = new CompletableFuture<>();

  OriginExecution join() throws InterruptedException {
    OriginExecution execution =
        executor.execute(
            decision,
            ignored -> {
              entered.countDown();
              return produced.join();
            });
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    return execution;
  }

  static void awaitUntracked(FlightLeaseRegistry registry) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (registry.trackedFlights() != 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    org.junit.jupiter.api.Assertions.assertEquals(0, registry.trackedFlights());
  }

  @Override
  public void close() {
    produced.completeExceptionally(new IllegalStateException("fixture closed"));
    executor.close();
  }
}
