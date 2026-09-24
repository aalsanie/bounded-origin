package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OriginExecutionLifetimeTest {
  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void ownershipIsReservedAtJoinAndOnlyLastOwnerReleases(boolean persist, boolean completeFirst)
      throws IOException {
    ManualThreadFactory workers = new ManualThreadFactory();
    Budget budget = new Budget(1, 0, Duration.ofSeconds(30), 10);
    TrackedBody body = new TrackedBody();
    Artifact artifact = new Artifact(1, Map.of(), body);
    var executor = executor(budget, workers);
    try {
      var selected = decision(policy(persist, budget), "shared");
      var first = executor.execute(selected, ignored -> artifact);
      var second =
          executor.execute(
              selected,
              ignored -> {
                throw new AssertionError();
              });
      try {
        assertFalse(first.joined());
        assertTrue(second.joined());
        assertSame(first.result(), second.result());
        if (completeFirst) {
          workers.run(0);
        }
        first.close();
        first.close();
        assertEquals(0, body.closes.get());
        if (!completeFirst) {
          assertEquals(1, executor.activeJobs());
          workers.run(0);
        }
        assertSame(artifact, second.result().toCompletableFuture().join());
        executor.close();
        try (InputStream input = artifact.body().openStream()) {
          assertEquals(7, input.read());
        }
        assertEquals(0, body.closes.get());
      } finally {
        first.close();
        second.close();
      }
      assertEquals(1, body.closes.get());
      assertThrows(IOException.class, body::openStream);
    } finally {
      executor.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abandonedFlightCanBeJoinedAgainBeforeCompletion(boolean rejoin) {
    ManualThreadFactory workers = new ManualThreadFactory();
    Budget budget = new Budget(1, 0, Duration.ofSeconds(30), 10);
    TrackedBody body = new TrackedBody();
    Artifact artifact = new Artifact(1, Map.of(), body);
    try (var executor = executor(budget, workers)) {
      var selected = decision(policy(false, budget), "shared");
      var first = executor.execute(selected, ignored -> artifact);
      first.close();
      assertEquals(1, executor.activeJobs());
      assertEquals(0, body.closes.get());
      OriginExecution follower =
          rejoin
              ? executor.execute(
                  selected,
                  ignored -> {
                    throw new AssertionError();
                  })
              : null;
      workers.run(0);
      assertEquals(rejoin ? 0 : 1, body.closes.get());
      if (follower != null) {
        assertTrue(follower.joined());
        assertSame(artifact, follower.result().toCompletableFuture().join());
        follower.close();
      }
      assertEquals(1, body.closes.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queuedAbandonmentReleasesLaterResultOrFailsWithoutProducing(boolean shutdown) {
    ManualThreadFactory workers = new ManualThreadFactory();
    Budget budget = new Budget(1, 1, Duration.ofSeconds(30), 10);
    TrackedBody firstBody = new TrackedBody();
    TrackedBody queuedBody = new TrackedBody();
    var executor = executor(budget, workers);
    try {
      var first =
          executor.execute(
              decision(policy(false, budget), "first"),
              ignored -> new Artifact(1, Map.of(), firstBody));
      var queued =
          executor.execute(
              decision(policy(false, budget), "queued"),
              ignored -> new Artifact(1, Map.of(), queuedBody));
      first.close();
      queued.close();
      if (shutdown) {
        executor.close();
        assertThrows(CompletionException.class, () -> queued.result().toCompletableFuture().join());
      }
      workers.run(0);
      if (!shutdown) {
        workers.run(1);
      }
      assertEquals(shutdown ? 0 : 1, firstBody.closes.get());
      assertEquals(shutdown ? 0 : 1, queuedBody.closes.get());
      assertEquals(0, executor.inFlightJobs());
    } finally {
      executor.close();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"io", "runtime"})
  void cleanupFailureDoesNotRepeatReleaseOrLoseAdmission(String failure) {
    ManualThreadFactory workers = new ManualThreadFactory();
    Budget budget = new Budget(1, 0, Duration.ofSeconds(30), 10);
    AtomicInteger closes = new AtomicInteger();
    ArtifactBody body =
        new ArtifactBody() {
          @Override
          public InputStream openStream() {
            return InputStream.nullInputStream();
          }

          @Override
          public void close() throws IOException {
            closes.incrementAndGet();
            if (failure.equals("io")) {
              throw new IOException("cleanup");
            }
            throw new IllegalStateException("cleanup");
          }
        };
    try (var executor = executor(budget, workers)) {
      var execution =
          executor.execute(
              decision(policy(false, budget), "one"), ignored -> new Artifact(0, Map.of(), body));
      workers.run(0);
      execution.close();
      execution.close();
      assertEquals(1, closes.get());
      try (var next =
          executor.execute(
              decision(policy(false, budget), "two"),
              ignored -> ExecutionTestSupport.artifact(0))) {
        assertFalse(next.joined());
        workers.run(1);
        assertEquals(0, next.result().toCompletableFuture().join().contentLength());
      }
    }
  }

  private static BoundedOriginExecutor executor(Budget budget, ManualThreadFactory workers) {
    return new BoundedOriginExecutor(
        budget, Duration.ofSeconds(1), 8, System::nanoTime, workers, new ManualThreadFactory());
  }

  private static OriginPolicy policy(boolean persist, Budget budget) {
    return persist
        ? ExecutionTestSupport.materializePolicy("p", budget)
        : ExecutionTestSupport.policy("p", budget);
  }

  private static final class TrackedBody implements ArtifactBody {
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public InputStream openStream() throws IOException {
      if (closes.get() != 0) {
        throw new IOException("released");
      }
      return new ByteArrayInputStream(new byte[] {7});
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }
}
