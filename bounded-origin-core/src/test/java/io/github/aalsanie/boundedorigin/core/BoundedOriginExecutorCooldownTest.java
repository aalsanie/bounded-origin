package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.failure;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorCooldownTest {
  @Test
  void materializationFailureSuppressesImmediateRetryAndExpiresDeterministically() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", budget);
    var selected = decision(policy, "same");
    AtomicLong now = new AtomicLong();
    AtomicInteger invocations = new AtomicInteger();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 4, now::get)) {
      assertEquals(
          OriginExecutionFailure.MATERIALIZATION_FAILED,
          failure(
                  executor.execute(
                      selected,
                      op -> {
                        invocations.incrementAndGet();
                        throw new MaterializationException("failed");
                      }))
              .failure());
      assertEquals(1, executor.cooldownEntries());

      assertEquals(
          OriginExecutionFailure.COOLDOWN,
          failure(
                  executor.execute(
                      selected,
                      op -> {
                        invocations.incrementAndGet();
                        return artifact(1);
                      }))
              .failure());
      assertEquals(1, invocations.get());

      now.set(Duration.ofSeconds(1).toNanos());
      assertEquals(
          1L,
          join(executor.execute(
                  selected,
                  op -> {
                    invocations.incrementAndGet();
                    return artifact(1);
                  }))
              .contentLength());
      assertEquals(2, invocations.get());
      assertEquals(0, executor.cooldownEntries());
    }
  }

  @Test
  void cooldownStorageIsStrictlyBoundedAndEvictsOldestFailure() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", budget);
    AtomicLong now = new AtomicLong();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(10), 2, now::get)) {
      for (String key : new String[] {"a", "b", "c"}) {
        failure(
            executor.execute(
                decision(policy, key),
                op -> {
                  throw new MaterializationException("failed");
                }));
      }

      assertEquals(2, executor.cooldownEntries());
      assertEquals(
          1L, join(executor.execute(decision(policy, "a"), op -> artifact(1))).contentLength());
      assertEquals(
          OriginExecutionFailure.COOLDOWN,
          failure(executor.execute(decision(policy, "b"), op -> artifact(1))).failure());
      assertEquals(
          OriginExecutionFailure.COOLDOWN,
          failure(executor.execute(decision(policy, "c"), op -> artifact(1))).failure());
    }
  }
}
