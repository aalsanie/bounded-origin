package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorRaceTest {
  @Test
  void simultaneousSameKeyCallersRaceIntoExactlyOneProducer() throws InterruptedException {
    int callers = 1_024;
    Budget budget = new Budget(4, 32, Duration.ofSeconds(30), 1_024);
    OriginPolicy selectedPolicy = policy("p", budget);
    var selected = decision(selectedPolicy, "same");
    Artifact expected = artifact(1);
    AtomicInteger invocations = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch returned = new CountDownLatch(callers);
    CountDownLatch producerStarted = new CountDownLatch(1);
    CountDownLatch releaseProducer = new CountDownLatch(1);
    List<CompletionStage<Artifact>> stages = Collections.synchronizedList(new ArrayList<>());
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    List<Thread> threads = new ArrayList<>();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 64)) {
      for (int index = 0; index < callers; index++) {
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      ready.countDown();
                      try {
                        ExecutionTestSupport.await(start);
                        stages.add(
                            executor.execute(
                                selected,
                                ignored -> {
                                  invocations.incrementAndGet();
                                  producerStarted.countDown();
                                  ExecutionTestSupport.await(releaseProducer);
                                  return expected;
                                }));
                      } catch (Throwable throwable) {
                        failures.add(throwable);
                      } finally {
                        returned.countDown();
                      }
                    }));
      }

      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(returned.await(10, TimeUnit.SECONDS));
      assertTrue(producerStarted.await(10, TimeUnit.SECONDS));
      assertTrue(failures.isEmpty(), failures::toString);
      assertEquals(callers, stages.size());

      CompletionStage<Artifact> shared = stages.getFirst();
      for (CompletionStage<Artifact> stage : stages) {
        assertSame(shared, stage);
      }
      assertEquals(1, invocations.get());
      assertEquals(1, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(1, executor.inFlightJobs());

      releaseProducer.countDown();
      assertSame(expected, join(shared));
      for (Thread thread : threads) {
        thread.join();
      }
    } finally {
      releaseProducer.countDown();
    }
  }
}
