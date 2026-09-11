package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorPerformanceTest {
  @Test
  void sameKeyJoinHotPathDoesNotRegressCatastrophically() {
    int joins = 100_000;
    Budget budget = new Budget(1, 32, Duration.ofSeconds(10), 1_024);
    var selected = decision(policy("p", budget), "same");
    Artifact expected = artifact(1);
    CountDownLatch releaseProducer = new CountDownLatch(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 64)) {
      CompletionStage<Artifact> shared =
          executor.execute(
              selected,
              ignored -> {
                ExecutionTestSupport.await(releaseProducer);
                return expected;
              });

      long started = System.nanoTime();
      for (int index = 0; index < joins; index++) {
        if (executor.execute(selected, ignored -> artifact(2)) != shared) {
          throw new AssertionError("same-key join returned a different shared stage");
        }
      }
      long elapsed = System.nanoTime() - started;

      assertTrue(
          elapsed < Duration.ofSeconds(10).toNanos(),
          () -> "100k same-key joins took " + Duration.ofNanos(elapsed));
      releaseProducer.countDown();
      assertSame(expected, join(shared));
    } finally {
      releaseProducer.countDown();
    }
  }
}
