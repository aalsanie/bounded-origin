package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.failure;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.materializePolicy;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorTest {
  @Test
  void executesBoundedComputeAndMaterializePolicies() {
    Budget budget = new Budget(2, 2, Duration.ofSeconds(2), 32);
    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 8)) {
      assertEquals(
          1L,
          join(executor.execute(decision(policy("compute", budget), "a"), op -> artifact(1)))
              .contentLength());
      assertEquals(
          2L,
          join(executor.execute(
                  decision(materializePolicy("materialize", budget), "b"), op -> artifact(2)))
              .contentLength());
    }
  }

  @Test
  void rejectsNonExecutableStrategiesSynchronously() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(1), 10);
    OriginPolicy artifactOnly =
        OriginPolicy.artifactOnly("artifact", 1, 1, "m", Canonicalizers.byDimensions());
    OriginPolicy client =
        OriginPolicy.clientCompute(
            "client",
            1,
            1,
            "m",
            Canonicalizers.byDimensions(),
            new ClientComputation("wasm", "1", Map.of()));

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 2)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> executor.execute(decision(artifactOnly, "a"), op -> artifact(1)));
      assertThrows(
          IllegalArgumentException.class,
          () -> executor.execute(decision(client, "b"), op -> artifact(1)));
    }
  }

  @Test
  void enforcesDeclaredResultSizeUsingStricterBudget() {
    Budget global = new Budget(1, 1, Duration.ofSeconds(2), 8);
    OriginPolicy policy = policy("p", new Budget(1, 1, Duration.ofSeconds(2), 4));

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(global, Duration.ofSeconds(1), 4)) {
      assertEquals(
          OriginExecutionFailure.RESULT_TOO_LARGE,
          failure(executor.execute(decision(policy, "large"), op -> artifact(5))).failure());
    }
  }

  @Test
  void mapsMaterializerFailuresAndNullResults() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(2), 16);
    OriginPolicy policy = policy("p", budget);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 4)) {
      OriginExecutionException checked =
          failure(
              executor.execute(
                  decision(policy, "checked"),
                  op -> {
                    throw new MaterializationException("failed");
                  }));
      OriginExecutionException runtime =
          failure(
              executor.execute(
                  decision(policy, "runtime"),
                  op -> {
                    throw new IllegalStateException("failed");
                  }));
      OriginExecutionException nullResult =
          failure(executor.execute(decision(policy, "null"), op -> null));

      assertEquals(OriginExecutionFailure.MATERIALIZATION_FAILED, checked.failure());
      assertEquals(OriginExecutionFailure.MATERIALIZATION_FAILED, runtime.failure());
      assertEquals(OriginExecutionFailure.MATERIALIZATION_FAILED, nullResult.failure());
    }
  }

  @Test
  void globalAndPolicyQueueLimitsAreIndependent() throws InterruptedException {
    Budget global = new Budget(1, 2, Duration.ofSeconds(5), 16);
    OriginPolicy limited = policy("limited", new Budget(1, 1, Duration.ofSeconds(5), 16));
    OriginPolicy other = policy("other", new Budget(1, 2, Duration.ofSeconds(5), 16));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(global, Duration.ofSeconds(1), 8)) {
      var active =
          executor.execute(
              decision(limited, "active"),
              op -> {
                started.countDown();
                ExecutionTestSupport.await(release);
                return artifact(1);
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));

      var limitedQueued = executor.execute(decision(limited, "queued"), op -> artifact(1));
      assertEquals(
          OriginExecutionFailure.POLICY_QUEUE_LIMIT,
          failure(executor.execute(decision(limited, "policy-full"), op -> artifact(1))).failure());

      var otherQueued = executor.execute(decision(other, "other-queued"), op -> artifact(1));
      assertEquals(
          OriginExecutionFailure.GLOBAL_QUEUE_LIMIT,
          failure(executor.execute(decision(other, "global-full"), op -> artifact(1))).failure());

      assertEquals(1, executor.activeJobs());
      assertEquals(2, executor.queuedJobs());
      assertEquals(3, executor.inFlightJobs());
      release.countDown();
      join(active);
      join(limitedQueued);
      join(otherQueued);
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(0, executor.inFlightJobs());
    }
  }

  @Test
  void globalBudgetCanBeStricterThanPolicyBudget() {
    Budget global = new Budget(1, 1, Duration.ofMillis(50), 3);
    OriginPolicy policy = policy("p", new Budget(1, 1, Duration.ofSeconds(5), 100));

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(global, Duration.ofSeconds(1), 4)) {
      assertEquals(
          OriginExecutionFailure.RESULT_TOO_LARGE,
          failure(executor.execute(decision(policy, "large"), op -> artifact(4))).failure());

      assertEquals(
          OriginExecutionFailure.TIMEOUT,
          failure(
                  executor.execute(
                      decision(policy, "timeout"),
                      op -> {
                        try {
                          Thread.sleep(200);
                        } catch (InterruptedException exception) {
                          Thread.currentThread().interrupt();
                        }
                        return artifact(1);
                      }))
              .failure());
    }
  }

  @Test
  void executeRejectsNullInputs() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(1), 10);
    OriginPolicy policy = policy("p", budget);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 2)) {
      assertThrows(NullPointerException.class, () -> executor.execute(null, op -> artifact(1)));
      assertThrows(NullPointerException.class, () -> executor.execute(decision(policy, "x"), null));
      assertThrows(NullPointerException.class, () -> executor.activeJobs(null));
      assertThrows(NullPointerException.class, () -> executor.queuedJobs(null));
    }
  }

  @Test
  void constructorRejectsInvalidConfiguration() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(1), 1);
    assertThrows(
        NullPointerException.class,
        () -> new BoundedOriginExecutor(null, Duration.ofSeconds(1), 1));
    assertThrows(NullPointerException.class, () -> new BoundedOriginExecutor(budget, null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new BoundedOriginExecutor(budget, Duration.ZERO, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(-1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 0));
    assertThrows(
        NullPointerException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 1, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(Long.MAX_VALUE), 1));
  }

  @Test
  void closedExecutorRejectsNewAndQueuedWork() throws InterruptedException {
    Budget budget = new Budget(1, 2, Duration.ofSeconds(30), 16);
    OriginPolicy policy = policy("p", budget);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger interruptions = new AtomicInteger();
    CountDownLatch interrupted = new CountDownLatch(1);

    BoundedOriginExecutor executor = new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 4);
    var active =
        executor.execute(
            decision(policy, "active"),
            op -> {
              started.countDown();
              while (true) {
                try {
                  release.await();
                  return artifact(1);
                } catch (InterruptedException exception) {
                  interruptions.incrementAndGet();
                  interrupted.countDown();
                }
              }
            });
    assertTrue(started.await(2, TimeUnit.SECONDS));
    var queued = executor.execute(decision(policy, "queued"), op -> artifact(1));

    executor.close();
    executor.close();

    assertEquals(OriginExecutionFailure.CLOSED, failure(active).failure());
    assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    assertTrue(interruptions.get() > 0);
    assertEquals(OriginExecutionFailure.CLOSED, failure(queued).failure());
    assertEquals(
        OriginExecutionFailure.CLOSED,
        failure(executor.execute(decision(policy, "new"), op -> artifact(1))).failure());
    assertEquals(0, executor.queuedJobs());
    release.countDown();
  }

  @Test
  void threadFactoryFailureDoesNotLeakCapacity() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(2), 16);
    OriginPolicy policy = policy("p", budget);
    ThreadFactory failing = runnable -> null;
    ThreadFactory unused = Thread.ofVirtual().factory();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 4, System::nanoTime, failing, unused)) {
      assertEquals(
          OriginExecutionFailure.INTERNAL_ERROR,
          failure(executor.execute(decision(policy, "x"), op -> artifact(1))).failure());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
    }
  }

  @Test
  void closeRacingWorkerCreationCannotLeakReservedCapacity() throws InterruptedException {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", budget);
    CountDownLatch factoryEntered = new CountDownLatch(1);
    CountDownLatch releaseFactory = new CountDownLatch(1);
    ThreadFactory blockedFactory =
        runnable -> {
          factoryEntered.countDown();
          ExecutionTestSupport.await(releaseFactory);
          return null;
        };
    AtomicReference<CompletionStage<io.github.aalsanie.boundedorigin.api.Artifact>> stage =
        new AtomicReference<>();

    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget,
            Duration.ofSeconds(1),
            4,
            System::nanoTime,
            blockedFactory,
            Thread.ofVirtual().factory());

    Thread caller =
        Thread.ofVirtual()
            .start(
                () -> stage.set(executor.execute(decision(policy, "racing"), op -> artifact(1))));

    assertTrue(factoryEntered.await(2, TimeUnit.SECONDS));
    assertEquals(1, executor.activeJobs());

    executor.close();
    releaseFactory.countDown();
    caller.join();

    assertEquals(OriginExecutionFailure.CLOSED, failure(stage.get()).failure());
    assertEquals(0, executor.activeJobs());
    assertEquals(0, executor.inFlightJobs());
  }

  @Test
  void timeoutThreadStartFailureKeepsSlotUntilWorkerPhysicallyStops() throws InterruptedException {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", budget);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger interruptions = new AtomicInteger();
    CountDownLatch interrupted = new CountDownLatch(1);
    ThreadFactory workers = Thread.ofVirtual().factory();
    ThreadFactory failingTimeout =
        runnable ->
            new Thread(runnable) {
              @Override
              public synchronized void start() {
                try {
                  if (!started.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("worker did not enter materializer");
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(exception);
                }
                throw new IllegalStateException("no timeout thread");
              }
            };

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 4, System::nanoTime, workers, failingTimeout)) {
      var first =
          executor.execute(
              decision(policy, "first"),
              op -> {
                started.countDown();
                while (true) {
                  try {
                    release.await();
                    return artifact(1);
                  } catch (InterruptedException exception) {
                    interruptions.incrementAndGet();
                    interrupted.countDown();
                  }
                }
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      assertEquals(OriginExecutionFailure.INTERNAL_ERROR, failure(first).failure());
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertTrue(interruptions.get() > 0);
      assertEquals(1, executor.activeJobs());

      var second = executor.execute(decision(policy, "second"), op -> artifact(1));
      assertFalse(second.toCompletableFuture().isDone());
      release.countDown();

      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (!second.toCompletableFuture().isDone() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertTrue(second.toCompletableFuture().isDone());
    }
  }
}
