package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorStressTest {
  @Test
  void oneHundredThousandSameKeyCallersCreateOneOriginJob() {
    Budget budget = new Budget(1, 32, Duration.ofSeconds(10), 1024);
    OriginPolicy policy = policy("p", budget);
    var selected = decision(policy, "same");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger invocations = new AtomicInteger();
    Artifact expected = artifact(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 64)) {
      CompletionStage<Artifact> shared =
          executor.execute(
              selected,
              op -> {
                invocations.incrementAndGet();
                started.countDown();
                ExecutionTestSupport.await(release);
                return expected;
              });
      ExecutionTestSupport.await(started);

      for (int index = 0; index < 100_000; index++) {
        assertSame(shared, executor.execute(selected, op -> artifact(2)));
      }

      assertEquals(1, invocations.get());
      assertEquals(1, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(1, executor.inFlightJobs());

      release.countDown();
      assertSame(expected, join(shared));
      assertEquals(1, invocations.get());
    }
  }

  @Test
  void oneHundredThousandUniqueKeysCannotGrowSchedulerBeyondConfiguredBounds() {
    Budget budget = new Budget(1, 32, Duration.ofSeconds(10), 1024);
    OriginPolicy policy = policy("p", budget);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean release = new AtomicBoolean();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 64)) {
      executor.execute(
          decision(policy, "0"),
          op -> {
            started.countDown();
            while (!release.get()) {
              try {
                Thread.sleep(5);
              } catch (InterruptedException exception) {
                if (release.get()) {
                  Thread.currentThread().interrupt();
                }
              }
            }
            return artifact(1);
          });
      ExecutionTestSupport.await(started);

      int queued = 0;
      int rejected = 0;
      for (int index = 1; index < 100_000; index++) {
        CompletionStage<Artifact> stage =
            executor.execute(decision(policy, Integer.toString(index)), op -> artifact(1));
        if (stage.toCompletableFuture().isCompletedExceptionally()) {
          rejected++;
        } else {
          queued++;
        }
      }

      assertEquals(32, queued);
      assertEquals(99_967, rejected);
      assertEquals(1, executor.activeJobs());
      assertEquals(32, executor.queuedJobs());
      assertEquals(33, executor.inFlightJobs());
    } finally {
      release.set(true);
    }
  }
}
