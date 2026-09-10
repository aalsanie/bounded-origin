package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.failure;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.materializePolicy;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorMutationTest {
  @Test
  void completionReleasesExactCapacityAndStartsQueuedWork() {
    Budget budget = new Budget(1, 2, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    Artifact firstArtifact = artifact(1);
    Artifact secondArtifact = artifact(2);

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> first =
          executor.execute(decision(selectedPolicy, "first"), ignored -> firstArtifact);
      CompletionStage<Artifact> second =
          executor.execute(decision(selectedPolicy, "second"), ignored -> secondArtifact);

      assertEquals(1, executor.activeJobs());
      assertEquals(1, executor.activeJobs("p"));
      assertEquals(1, executor.queuedJobs());
      assertEquals(1, executor.queuedJobs("p"));
      assertEquals(2, executor.inFlightJobs());
      assertEquals(1, workers.created());
      assertEquals(1, timeouts.created());
      assertTrue(workers.started(0));
      assertTrue(timeouts.started(0));
      assertFalse(first.toCompletableFuture().isDone());
      assertFalse(second.toCompletableFuture().isDone());

      workers.run(0);

      assertTrue(first.toCompletableFuture().isDone());
      assertSame(firstArtifact, join(first));
      assertTrue(timeouts.interrupted(0));
      assertEquals(1, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(1, executor.inFlightJobs());
      assertEquals(2, workers.created());
      assertEquals(2, timeouts.created());
      assertTrue(workers.started(1));
      assertTrue(timeouts.started(1));

      workers.run(1);

      assertTrue(second.toCompletableFuture().isDone());
      assertSame(secondArtifact, join(second));
      assertTrue(timeouts.interrupted(1));
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.activeJobs("p"));
      assertEquals(0, executor.queuedJobs());
      assertEquals(0, executor.queuedJobs("p"));
      assertEquals(0, executor.inFlightJobs());
    }
  }

  @Test
  void sameKeyUsesOneJobAndOneStage() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    AtomicInteger invocations = new AtomicInteger();
    Artifact expected = artifact(3);
    var selected = decision(selectedPolicy, "same");

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> first =
          executor.execute(
              selected,
              ignored -> {
                invocations.incrementAndGet();
                return expected;
              });
      CompletionStage<Artifact> second =
          executor.execute(
              selected,
              ignored -> {
                invocations.addAndGet(100);
                return artifact(4);
              });

      assertSame(first, second);
      assertEquals(1, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(1, executor.inFlightJobs());
      assertEquals(1, workers.created());

      workers.run(0);

      assertTrue(first.toCompletableFuture().isDone());
      assertSame(expected, join(first));
      assertEquals(1, invocations.get());
      assertEquals(0, executor.inFlightJobs());
    }
  }

  @Test
  void queueLimitsUseExactGlobalAndPolicyBoundaries() {
    Budget global = new Budget(1, 2, Duration.ofSeconds(5), 16);
    OriginPolicy limited = policy("limited", new Budget(1, 1, Duration.ofSeconds(5), 16));
    OriginPolicy other = policy("other", new Budget(1, 2, Duration.ofSeconds(5), 16));
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor = executor(global, workers, timeouts)) {
      CompletionStage<Artifact> active =
          executor.execute(decision(limited, "active"), ignored -> artifact(1));
      CompletionStage<Artifact> limitedQueued =
          executor.execute(decision(limited, "limited-queued"), ignored -> artifact(1));
      CompletionStage<Artifact> policyRejected =
          executor.execute(decision(limited, "policy-rejected"), ignored -> artifact(1));
      CompletionStage<Artifact> otherQueued =
          executor.execute(decision(other, "other-queued"), ignored -> artifact(1));
      CompletionStage<Artifact> globalRejected =
          executor.execute(decision(other, "global-rejected"), ignored -> artifact(1));

      assertFailure(policyRejected, OriginExecutionFailure.POLICY_QUEUE_LIMIT);
      assertFailure(globalRejected, OriginExecutionFailure.GLOBAL_QUEUE_LIMIT);
      assertEquals(1, executor.activeJobs());
      assertEquals(2, executor.queuedJobs());
      assertEquals(1, executor.queuedJobs("limited"));
      assertEquals(1, executor.queuedJobs("other"));
      assertEquals(3, executor.inFlightJobs());

      workers.run(0);
      assertTrue(active.toCompletableFuture().isDone());
      assertEquals(2, workers.created());
      workers.run(1);
      assertTrue(limitedQueued.toCompletableFuture().isDone());
      assertEquals(3, workers.created());
      workers.run(2);
      assertTrue(otherQueued.toCompletableFuture().isDone());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(0, executor.inFlightJobs());
    }
  }

  @Test
  void roundRobinSkipsPolicyAtItsActiveLimitWithoutLosingQueueState() {
    Budget global = new Budget(2, 4, Duration.ofSeconds(5), 16);
    OriginPolicy first = policy("first", new Budget(1, 4, Duration.ofSeconds(5), 16));
    OriginPolicy second = policy("second", new Budget(2, 4, Duration.ofSeconds(5), 16));
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor = executor(global, workers, timeouts)) {
      CompletionStage<Artifact> firstActive =
          executor.execute(decision(first, "first-active"), ignored -> artifact(1));
      CompletionStage<Artifact> secondActive =
          executor.execute(decision(second, "second-active"), ignored -> artifact(1));
      CompletionStage<Artifact> firstQueued =
          executor.execute(decision(first, "first-queued"), ignored -> artifact(1));
      CompletionStage<Artifact> secondQueued =
          executor.execute(decision(second, "second-queued"), ignored -> artifact(1));

      assertEquals(2, executor.activeJobs());
      assertEquals(2, executor.queuedJobs());
      workers.run(1);

      assertTrue(secondActive.toCompletableFuture().isDone());
      assertEquals(3, workers.created());
      assertEquals(2, executor.activeJobs());
      assertEquals(1, executor.queuedJobs());
      assertEquals(1, executor.queuedJobs("first"));
      assertFalse(firstQueued.toCompletableFuture().isDone());

      workers.run(2);
      assertTrue(secondQueued.toCompletableFuture().isDone());
      assertEquals(3, workers.created());
      assertEquals(1, executor.activeJobs());
      assertEquals(1, executor.queuedJobs());

      workers.run(0);
      assertTrue(firstActive.toCompletableFuture().isDone());
      assertEquals(4, workers.created());
      assertEquals(1, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());

      workers.run(3);
      assertTrue(firstQueued.toCompletableFuture().isDone());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
    }
  }

  @Test
  void timeoutFailsStageInterruptsWorkerAndKeepsCapacityUntilWorkerStops()
      throws InterruptedException {
    Budget global = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", new Budget(1, 0, Duration.ofNanos(1), 16));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    AtomicInteger interruptions = new AtomicInteger();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    ThreadFactory workers = Thread.ofVirtual().factory();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            global, Duration.ofSeconds(1), 8, System::nanoTime, workers, timeouts)) {
      CompletionStage<Artifact> first =
          executor.execute(
              decision(selectedPolicy, "first"),
              ignored -> {
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

      assertEquals(1, timeouts.created());
      timeouts.run(0);
      assertFailure(first, OriginExecutionFailure.TIMEOUT);
      assertEquals(1, executor.activeJobs());
      assertEquals(1, executor.inFlightJobs());
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertTrue(interruptions.get() > 0);
      release.countDown();

      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (executor.activeJobs() != 0) {
        if (System.nanoTime() >= deadline) {
          throw new AssertionError("timed out waiting for worker to stop");
        }
        Thread.onSpinWait();
      }
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
    } finally {
      release.countDown();
    }
  }

  @Test
  void timeoutBeforeWorkerEntryDoesNotRunMaterializerOrRecordFailureCooldown() {
    Budget budget = new Budget(1, 0, Duration.ofNanos(1), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    AtomicInteger invocations = new AtomicInteger();

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> stage =
          executor.execute(
              decision(selectedPolicy, "timeout"),
              ignored -> {
                invocations.incrementAndGet();
                return artifact(1);
              });

      timeouts.run(0);
      assertFailure(stage, OriginExecutionFailure.TIMEOUT);
      assertTrue(workers.interrupted(0));
      assertEquals(1, executor.activeJobs());
      workers.run(0);
      assertEquals(0, invocations.get());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(0, executor.cooldownEntries());
    }
  }

  @Test
  void materializerFailuresPreserveCauseReleaseCapacityAndEnterCooldown() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    MaterializationException cause = new MaterializationException("origin failed");

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> stage =
          executor.execute(
              decision(selectedPolicy, "failed"),
              ignored -> {
                throw cause;
              });
      workers.run(0);

      OriginExecutionException failure =
          assertFailure(stage, OriginExecutionFailure.MATERIALIZATION_FAILED);
      assertSame(cause, failure.getCause());
      assertEquals("origin materialization failed", failure.getMessage());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
      assertFailure(
          executor.execute(decision(selectedPolicy, "failed"), ignored -> artifact(1)),
          OriginExecutionFailure.COOLDOWN);
    }
  }

  @Test
  void fatalMaterializerErrorCompletesSharedFailureCleansStateAndRethrows() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    AssertionError fatal = new AssertionError("fatal");

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> stage =
          executor.execute(
              decision(selectedPolicy, "fatal"),
              ignored -> {
                throw fatal;
              });

      AssertionError thrown = assertThrows(AssertionError.class, () -> workers.run(0));
      assertSame(fatal, thrown);
      OriginExecutionException failure =
          assertFailure(stage, OriginExecutionFailure.MATERIALIZATION_FAILED);
      assertSame(fatal, failure.getCause());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
    }
  }

  @Test
  void closeFailsAllOwnedStagesClearsQueuedStateAndRetainsRunningAccounting() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    BoundedOriginExecutor executor = executor(budget, workers, timeouts);

    CompletionStage<Artifact> active =
        executor.execute(decision(selectedPolicy, "active"), ignored -> artifact(1));
    CompletionStage<Artifact> queued =
        executor.execute(decision(selectedPolicy, "queued"), ignored -> artifact(1));

    executor.close();
    executor.close();

    assertFailure(active, OriginExecutionFailure.CLOSED);
    assertFailure(queued, OriginExecutionFailure.CLOSED);
    assertTrue(workers.interrupted(0));
    assertTrue(timeouts.interrupted(0));
    assertEquals(1, executor.activeJobs());
    assertEquals(0, executor.queuedJobs());
    assertEquals(1, executor.inFlightJobs());
    assertEquals(0, executor.queuedJobs("p"));
    assertFailure(
        executor.execute(decision(selectedPolicy, "new"), ignored -> artifact(1)),
        OriginExecutionFailure.CLOSED);

    workers.run(0);
    assertEquals(0, executor.activeJobs());
    assertEquals(0, executor.inFlightJobs());
  }

  @Test
  void closeClearsExistingCooldownState() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    BoundedOriginExecutor executor = executor(budget, workers, timeouts);

    CompletionStage<Artifact> failed =
        executor.execute(
            decision(selectedPolicy, "failed"),
            ignored -> {
              throw new MaterializationException("failed");
            });
    workers.run(0);
    assertFailure(failed, OriginExecutionFailure.MATERIALIZATION_FAILED);
    assertEquals(1, executor.cooldownEntries());

    executor.close();

    assertEquals(0, executor.cooldownEntries());
  }

  @Test
  void cooldownExpiryAndOldestEntryEvictionAreExact() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    AtomicLong now = new AtomicLong();
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofNanos(10), 2, now::get, workers, timeouts)) {
      failMaterialization(executor, workers, selectedPolicy, "a", 0);
      now.set(1);
      failMaterialization(executor, workers, selectedPolicy, "b", 1);
      now.set(2);
      failMaterialization(executor, workers, selectedPolicy, "c", 2);

      assertEquals(2, executor.cooldownEntries());
      CompletionStage<Artifact> a =
          executor.execute(decision(selectedPolicy, "a"), ignored -> artifact(1));
      workers.run(3);
      assertEquals(1L, join(a).contentLength());
      assertFailure(
          executor.execute(decision(selectedPolicy, "b"), ignored -> artifact(1)),
          OriginExecutionFailure.COOLDOWN);

      now.set(11);
      assertEquals(1, executor.cooldownEntries());
      assertFailure(
          executor.execute(decision(selectedPolicy, "c"), ignored -> artifact(1)),
          OriginExecutionFailure.COOLDOWN);
      now.set(12);
      assertEquals(0, executor.cooldownEntries());
    }
  }

  @Test
  void workerCreationAndStartFailuresReleaseCapacityAndPreserveCause() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    IllegalStateException creationFailure = new IllegalStateException("create");
    ThreadFactory throwingFactory =
        ignored -> {
          throw creationFailure;
        };
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 4, System::nanoTime, throwingFactory, timeouts)) {
      OriginExecutionException failure =
          assertFailure(
              executor.execute(decision(selectedPolicy, "create"), ignored -> artifact(1)),
              OriginExecutionFailure.INTERNAL_ERROR);
      assertSame(creationFailure, failure.getCause());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
    }

    IllegalStateException startFailure = new IllegalStateException("start");
    ManualThreadFactory failingWorkers = new ManualThreadFactory(0, startFailure);
    ManualThreadFactory secondTimeouts = new ManualThreadFactory();
    try (BoundedOriginExecutor executor = executor(budget, failingWorkers, secondTimeouts)) {
      OriginExecutionException failure =
          assertFailure(
              executor.execute(decision(selectedPolicy, "start"), ignored -> artifact(1)),
              OriginExecutionFailure.INTERNAL_ERROR);
      assertSame(startFailure, failure.getCause());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
      assertEquals(1, secondTimeouts.created());
      assertFalse(secondTimeouts.started(0));
    }
  }

  @Test
  void timeoutCreationAndStartFailuresFollowWorkerPhysicalLifetime() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ThreadFactory nullTimeouts = ignored -> null;

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 4, System::nanoTime, workers, nullTimeouts)) {
      assertFailure(
          executor.execute(decision(selectedPolicy, "create"), ignored -> artifact(1)),
          OriginExecutionFailure.INTERNAL_ERROR);
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, workers.created());
      assertFalse(workers.started(0));
    }

    ManualThreadFactory secondWorkers = new ManualThreadFactory();
    IllegalStateException startFailure = new IllegalStateException("timeout-start");
    ManualThreadFactory failingTimeouts = new ManualThreadFactory(0, startFailure);
    try (BoundedOriginExecutor executor = executor(budget, secondWorkers, failingTimeouts)) {
      CompletionStage<Artifact> stage =
          executor.execute(decision(selectedPolicy, "start"), ignored -> artifact(1));
      OriginExecutionException failure =
          assertFailure(stage, OriginExecutionFailure.INTERNAL_ERROR);
      assertSame(startFailure, failure.getCause());
      assertTrue(secondWorkers.started(0));
      assertTrue(secondWorkers.interrupted(0));
      assertEquals(1, executor.activeJobs());
      assertEquals(1, executor.inFlightJobs());
      secondWorkers.run(0);
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(0, executor.cooldownEntries());
    }
  }

  @Test
  void samePolicyActiveAccountingDecrementsOneJobAtATime() {
    Budget budget = new Budget(2, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> first =
          executor.execute(decision(selectedPolicy, "first"), ignored -> artifact(1));
      CompletionStage<Artifact> second =
          executor.execute(decision(selectedPolicy, "second"), ignored -> artifact(1));

      assertEquals(2, executor.activeJobs());
      assertEquals(2, executor.activeJobs("p"));
      workers.run(0);
      assertTrue(first.toCompletableFuture().isDone());
      assertEquals(1, executor.activeJobs());
      assertEquals(1, executor.activeJobs("p"));
      workers.run(1);
      assertTrue(second.toCompletableFuture().isDone());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.activeJobs("p"));
    }
  }

  @Test
  void closeDuringThreadCreationStopsReservedJobBeforeThreadsStart() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    java.util.concurrent.atomic.AtomicReference<BoundedOriginExecutor> executorRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    ThreadFactory closingTimeouts =
        runnable -> {
          executorRef.get().close();
          return timeouts.newThread(runnable);
        };
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 4, System::nanoTime, workers, closingTimeouts);
    executorRef.set(executor);

    CompletionStage<Artifact> stage =
        executor.execute(decision(selectedPolicy, "close"), ignored -> artifact(1));

    assertFailure(stage, OriginExecutionFailure.CLOSED);
    assertEquals(0, executor.activeJobs());
    assertEquals(0, executor.inFlightJobs());
    assertEquals(1, workers.created());
    assertEquals(1, timeouts.created());
    assertFalse(workers.started(0));
    assertFalse(timeouts.started(0));
  }

  @Test
  void completedWorkerIsNotConvertedToFailureWhenTimeoutStartThenFails() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    Artifact expected = artifact(7);
    AtomicInteger invocations = new AtomicInteger();
    ThreadFactory inlineWorkers =
        runnable ->
            new Thread() {
              @Override
              public synchronized void start() {
                runnable.run();
              }
            };
    ManualThreadFactory failingTimeouts =
        new ManualThreadFactory(0, new IllegalStateException("timeout-start"));

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 4, System::nanoTime, inlineWorkers, failingTimeouts)) {
      CompletionStage<Artifact> stage =
          executor.execute(
              decision(selectedPolicy, "fast"),
              ignored -> {
                invocations.incrementAndGet();
                return expected;
              });

      assertTrue(stage.toCompletableFuture().isDone());
      assertSame(expected, join(stage));
      assertEquals(1, invocations.get());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(0, executor.cooldownEntries());
      assertTrue(failingTimeouts.interrupted(0));
    }
  }

  @Test
  void nullFactoriesAndNegativeCooldownCapacityAreRejected() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    ManualThreadFactory factory = new ManualThreadFactory();

    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(1), -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 0));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedOriginExecutor(
                budget, Duration.ofSeconds(1), 1, System::nanoTime, null, factory));
    assertThrows(
        NullPointerException.class,
        () ->
            new BoundedOriginExecutor(
                budget, Duration.ofSeconds(1), 1, System::nanoTime, factory, null));
  }

  @Test
  void nullContractsFailSynchronouslyBeforeAnySchedulerStateChanges() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      NullPointerException nullDecision =
          assertThrows(
              NullPointerException.class, () -> executor.execute(null, ignored -> artifact(1)));
      assertEquals("decision", nullDecision.getMessage());
      NullPointerException nullMaterializer =
          assertThrows(
              NullPointerException.class,
              () -> executor.execute(decision(selectedPolicy, "null-materializer"), null));
      assertEquals("materializer", nullMaterializer.getMessage());
      assertThrows(NullPointerException.class, () -> executor.activeJobs(null));
      assertThrows(NullPointerException.class, () -> executor.queuedJobs(null));
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.queuedJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(0, workers.created());
      assertEquals(0, timeouts.created());
    }

    assertThrows(
        NullPointerException.class,
        () -> new BoundedOriginExecutor(null, Duration.ofSeconds(1), 1));
    assertThrows(NullPointerException.class, () -> new BoundedOriginExecutor(budget, null, 1));
    assertThrows(
        NullPointerException.class,
        () -> new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 1, null));
  }

  @Test
  void runtimeAndNullMaterializerFailuresReleaseCapacityAndEnterCooldown() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    IllegalStateException runtime = new IllegalStateException("runtime");

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> runtimeStage =
          executor.execute(
              decision(selectedPolicy, "runtime"),
              ignored -> {
                throw runtime;
              });
      workers.run(0);
      OriginExecutionException runtimeFailure =
          assertFailure(runtimeStage, OriginExecutionFailure.MATERIALIZATION_FAILED);
      assertSame(runtime, runtimeFailure.getCause());
      assertEquals(0, executor.activeJobs());

      CompletionStage<Artifact> nullStage =
          executor.execute(decision(selectedPolicy, "null-result"), ignored -> null);
      workers.run(1);
      OriginExecutionException nullFailure =
          assertFailure(nullStage, OriginExecutionFailure.MATERIALIZATION_FAILED);
      assertTrue(nullFailure.getCause() instanceof NullPointerException);
      assertEquals("materializer result", nullFailure.getCause().getMessage());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(2, executor.cooldownEntries());
    }
  }

  @Test
  void strategyGateAcceptsBothExecutionStrategiesAndRejectsArtifactOnly() {
    Budget budget = new Budget(1, 0, Duration.ofSeconds(5), 16);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    OriginPolicy bounded = policy("bounded", budget);
    OriginPolicy materialize = materializePolicy("materialize", budget);
    OriginPolicy artifactOnly =
        OriginPolicy.artifactOnly(
            "artifact", 1, 1, "materializer-1", Canonicalizers.byDimensions());

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> first =
          executor.execute(decision(bounded, "bounded"), ignored -> artifact(1));
      workers.run(0);
      assertEquals(1L, join(first).contentLength());

      CompletionStage<Artifact> second =
          executor.execute(decision(materialize, "materialize"), ignored -> artifact(2));
      workers.run(1);
      assertEquals(2L, join(second).contentLength());

      assertThrows(
          IllegalArgumentException.class,
          () -> executor.execute(decision(artifactOnly, "artifact"), ignored -> artifact(3)));
      assertEquals(2, workers.created());
    }
  }

  @Test
  void resultLimitUsesExactMinimumAndAllowsExactBoundary() {
    Budget global = new Budget(1, 0, Duration.ofSeconds(5), 8);
    OriginPolicy policy = policy("p", new Budget(1, 0, Duration.ofSeconds(5), 5));
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor = executor(global, workers, timeouts)) {
      CompletionStage<Artifact> exact =
          executor.execute(decision(policy, "exact"), ignored -> artifact(5));
      workers.run(0);
      assertEquals(5L, join(exact).contentLength());

      CompletionStage<Artifact> over =
          executor.execute(decision(policy, "over"), ignored -> artifact(6));
      workers.run(1);
      assertFailure(over, OriginExecutionFailure.RESULT_TOO_LARGE);
    }

    Budget strictGlobal = new Budget(1, 0, Duration.ofSeconds(5), 5);
    OriginPolicy loosePolicy = policy("p", new Budget(1, 0, Duration.ofSeconds(5), 8));
    ManualThreadFactory secondWorkers = new ManualThreadFactory();
    ManualThreadFactory secondTimeouts = new ManualThreadFactory();
    try (BoundedOriginExecutor executor = executor(strictGlobal, secondWorkers, secondTimeouts)) {
      CompletionStage<Artifact> over =
          executor.execute(decision(loosePolicy, "global-over"), ignored -> artifact(6));
      secondWorkers.run(0);
      assertFailure(over, OriginExecutionFailure.RESULT_TOO_LARGE);
    }
  }

  @Test
  void multipleQueuedJobsForOnePolicyRemainReachableAfterEachDrain() {
    Budget budget = new Budget(1, 2, Duration.ofSeconds(5), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> active =
          executor.execute(decision(selectedPolicy, "active"), ignored -> artifact(1));
      CompletionStage<Artifact> queuedOne =
          executor.execute(decision(selectedPolicy, "queued-1"), ignored -> artifact(2));
      CompletionStage<Artifact> queuedTwo =
          executor.execute(decision(selectedPolicy, "queued-2"), ignored -> artifact(3));

      assertEquals(1, workers.created());
      assertEquals(2, executor.queuedJobs());
      workers.run(0);
      assertEquals(2, workers.created());
      assertEquals(1, executor.queuedJobs());
      assertEquals(1L, join(active).contentLength());

      workers.run(1);
      assertEquals(3, workers.created());
      assertEquals(0, executor.queuedJobs());
      assertEquals(2L, join(queuedOne).contentLength());

      workers.run(2);
      assertEquals(3L, join(queuedTwo).contentLength());
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
    }
  }

  @Test
  void fatalErrorAfterTimeoutRetainsTimeoutResultCleansStateAndRethrows() {
    Budget budget = new Budget(1, 0, Duration.ofNanos(1), 16);
    OriginPolicy selectedPolicy = policy("p", budget);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    AssertionError fatal = new AssertionError("fatal-after-timeout");

    try (BoundedOriginExecutor executor = executor(budget, workers, timeouts)) {
      CompletionStage<Artifact> stage =
          executor.execute(
              decision(selectedPolicy, "fatal-timeout"),
              ignored -> {
                timeouts.run(0);
                throw fatal;
              });

      AssertionError thrown = assertThrows(AssertionError.class, () -> workers.run(0));
      assertSame(fatal, thrown);
      assertFailure(stage, OriginExecutionFailure.TIMEOUT);
      assertTrue(workers.interrupted(0));
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(1, executor.cooldownEntries());
    }
  }

  private static BoundedOriginExecutor executor(
      Budget budget, ManualThreadFactory workers, ManualThreadFactory timeouts) {
    return new BoundedOriginExecutor(
        budget, Duration.ofSeconds(1), 8, System::nanoTime, workers, timeouts);
  }

  private static OriginExecutionException assertFailure(
      CompletionStage<Artifact> stage, OriginExecutionFailure expected) {
    assertTrue(stage.toCompletableFuture().isDone());
    OriginExecutionException exception = failure(stage);
    assertEquals(expected, exception.failure());
    assertTrue(exception.getMessage() != null && !exception.getMessage().isBlank());
    return exception;
  }

  private static void failMaterialization(
      BoundedOriginExecutor executor,
      ManualThreadFactory workers,
      OriginPolicy selectedPolicy,
      String identity,
      int workerIndex) {
    CompletionStage<Artifact> stage =
        executor.execute(
            decision(selectedPolicy, identity),
            ignored -> {
              throw new MaterializationException("failed");
            });
    workers.run(workerIndex);
    assertFailure(stage, OriginExecutionFailure.MATERIALIZATION_FAILED);
  }
}
