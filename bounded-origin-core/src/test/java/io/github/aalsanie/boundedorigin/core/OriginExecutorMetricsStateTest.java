package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.failure;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OriginExecutorMetricsStateTest {
  @Test
  void snapshotCapturesActiveQueuedAndInFlightStateAtomically() throws InterruptedException {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(10), 1_024);
    var selectedFirst = decision(policy("p", budget), "first");
    var selectedSecond = decision(policy("p", budget), "second");
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 8)) {
      var first =
          executor.execute(
              selectedFirst,
              ignored -> {
                firstStarted.countDown();
                ExecutionTestSupport.await(releaseFirst);
                return artifact(1);
              });
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

      var second = executor.execute(selectedSecond, ignored -> artifact(2));

      assertEquals(new OriginExecutorStats(1, 1, 2, 0), OriginExecutorMetrics.snapshot(executor));

      releaseFirst.countDown();
      join(first);
      join(second);
      assertEquals(new OriginExecutorStats(0, 0, 0, 0), OriginExecutorMetrics.snapshot(executor));
    } finally {
      releaseFirst.countDown();
    }
  }

  @Test
  void snapshotIncludesFailureCooldownFromTheSameExecutorState() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(10), 1_024);
    var selected = decision(policy("p", budget), "failure");

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(10), 8)) {
      var failed =
          executor.execute(
              selected,
              ignored -> {
                throw new IllegalStateException("expected");
              });

      assertEquals(OriginExecutionFailure.MATERIALIZATION_FAILED, failure(failed).failure());
      assertEquals(new OriginExecutorStats(0, 0, 0, 1), OriginExecutorMetrics.snapshot(executor));
    }
  }
}
