package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.failure;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedOriginExecutorConcurrencyTest {
  @Test
  void identicalCallersShareOneProducerAndOneReadOnlyStage() throws InterruptedException {
    Budget budget = new Budget(2, 32, Duration.ofSeconds(5), 1024);
    OriginPolicy policy = policy("p", budget);
    var selected = decision(policy, "same");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger invocations = new AtomicInteger();
    Artifact expected = artifact(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 64)) {
      CompletionStage<Artifact> first =
          executor.execute(
              selected,
              op -> {
                invocations.incrementAndGet();
                started.countDown();
                ExecutionTestSupport.await(release);
                return expected;
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));

      List<CompletionStage<Artifact>> followers = new ArrayList<>();
      for (int index = 0; index < 10_000; index++) {
        followers.add(executor.execute(selected, op -> artifact(2)));
      }

      for (CompletionStage<Artifact> follower : followers) {
        assertSame(first, follower);
      }
      assertEquals(1, invocations.get());
      assertEquals(1, executor.activeJobs());
      assertEquals(1, executor.inFlightJobs());

      release.countDown();
      assertSame(expected, join(first));
      for (CompletionStage<Artifact> follower : followers) {
        assertSame(expected, join(follower));
      }
      assertEquals(1, invocations.get());
    }
  }

  @Test
  void cancellingDerivedFollowerCannotCancelSharedComputation() throws InterruptedException {
    Budget budget = new Budget(1, 2, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", budget);
    var selected = decision(policy, "same");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger invocations = new AtomicInteger();
    Artifact expected = artifact(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 8)) {
      CompletionStage<Artifact> shared =
          executor.execute(
              selected,
              op -> {
                invocations.incrementAndGet();
                started.countDown();
                ExecutionTestSupport.await(release);
                return expected;
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));

      var callerFuture = shared.toCompletableFuture();
      assertTrue(callerFuture.cancel(true));
      assertThrowsReadOnly(shared);
      CompletionStage<Artifact> follower = executor.execute(selected, op -> artifact(2));
      assertFalse(follower.toCompletableFuture().isDone());

      release.countDown();
      assertSame(expected, join(follower));
      assertEquals(1, invocations.get());
    }
  }

  @Test
  void activeComputationNeverExceedsGlobalOrPolicyLimits() {
    Budget global = new Budget(4, 512, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", new Budget(3, 512, Duration.ofSeconds(5), 16));
    AtomicInteger active = new AtomicInteger();
    AtomicInteger observedMaximum = new AtomicInteger();
    List<CompletionStage<Artifact>> stages = new ArrayList<>();

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(global, Duration.ofSeconds(1), 64)) {
      for (int index = 0; index < 300; index++) {
        stages.add(
            executor.execute(
                decision(policy, "key-" + index),
                op -> {
                  int current = active.incrementAndGet();
                  observedMaximum.accumulateAndGet(current, Math::max);
                  try {
                    Thread.sleep(2);
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                  } finally {
                    active.decrementAndGet();
                  }
                  return artifact(1);
                }));
      }

      for (CompletionStage<Artifact> stage : stages) {
        join(stage);
      }

      assertTrue(observedMaximum.get() <= 3);
      assertTrue(executor.activeJobs() <= global.maxActive());
      assertTrue(executor.activeJobs(policy.id()) <= 3);
    }
  }

  @Test
  void timedOutInterruptIgnoringProducerRetainsItsActiveSlot() throws InterruptedException {
    Budget global = new Budget(1, 1, Duration.ofSeconds(5), 16);
    OriginPolicy policy = policy("p", new Budget(1, 1, Duration.ofMillis(75), 16));
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicBoolean release = new AtomicBoolean();
    AtomicInteger interruptions = new AtomicInteger();
    CountDownLatch interrupted = new CountDownLatch(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(global, Duration.ofSeconds(1), 8)) {
      CompletionStage<Artifact> first =
          executor.execute(
              decision(policy, "first"),
              op -> {
                firstStarted.countDown();
                while (!release.get()) {
                  try {
                    Thread.sleep(5);
                  } catch (InterruptedException exception) {
                    interruptions.incrementAndGet();
                    interrupted.countDown();
                  }
                }
                return artifact(1);
              });
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

      CompletionStage<Artifact> second =
          executor.execute(
              decision(policy, "second"),
              op -> {
                secondStarted.countDown();
                return artifact(1);
              });

      assertEquals(OriginExecutionFailure.TIMEOUT, failure(first).failure());
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertTrue(interruptions.get() > 0);
      assertEquals(1, executor.activeJobs());
      assertFalse(secondStarted.await(150, TimeUnit.MILLISECONDS));

      release.set(true);
      assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
      join(second);
    } finally {
      release.set(true);
    }
  }

  @Test
  void roundRobinQueuePreventsOnePolicyFromStarvingAnother() throws InterruptedException {
    Budget global = new Budget(1, 8, Duration.ofSeconds(5), 16);
    OriginPolicy firstPolicy = policy("first", new Budget(1, 8, Duration.ofSeconds(5), 16));
    OriginPolicy secondPolicy = policy("second", new Budget(1, 8, Duration.ofSeconds(5), 16));
    List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(global, Duration.ofSeconds(1), 8)) {
      var first =
          executor.execute(
              decision(firstPolicy, "first-0"),
              op -> {
                order.add("first-0");
                firstStarted.countDown();
                ExecutionTestSupport.await(releaseFirst);
                return artifact(1);
              });
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

      var firstOne =
          executor.execute(
              decision(firstPolicy, "first-1"),
              op -> {
                order.add("first-1");
                return artifact(1);
              });
      var secondZero =
          executor.execute(
              decision(secondPolicy, "second-0"),
              op -> {
                order.add("second-0");
                return artifact(1);
              });
      var firstTwo =
          executor.execute(
              decision(firstPolicy, "first-2"),
              op -> {
                order.add("first-2");
                return artifact(1);
              });

      releaseFirst.countDown();
      join(first);
      join(firstOne);
      join(secondZero);
      join(firstTwo);

      assertEquals(List.of("first-0", "first-1", "second-0", "first-2"), order);
    }
  }

  private static void assertThrowsReadOnly(CompletionStage<Artifact> stage) {
    try {
      ((CompletableFuture<Artifact>) stage).cancel(true);
      throw new AssertionError("shared stage must be read-only");
    } catch (UnsupportedOperationException expected) {
    }
  }
}
