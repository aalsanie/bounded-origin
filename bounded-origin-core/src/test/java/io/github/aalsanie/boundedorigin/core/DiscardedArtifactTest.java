package io.github.aalsanie.boundedorigin.core;

import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.decision;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.failure;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.join;
import static io.github.aalsanie.boundedorigin.core.ExecutionTestSupport.policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import io.github.aalsanie.boundedorigin.api.Budget;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DiscardedArtifactTest {
  @ParameterizedTest
  @CsvSource({
    "success,none",
    "oversized,none",
    "timeout,none",
    "closed,none",
    "timeout,io",
    "timeout,runtime",
    "timeout,error",
    "oversized,error"
  })
  void discardedResultReleasesItsBodyExactlyOnceWithoutLosingCapacity(
      String outcome, String closeFailure) {
    Budget budget = new Budget(1, 0, Duration.ofMillis(1), 16);
    ManualThreadFactory workers = new ManualThreadFactory();
    ManualThreadFactory timeouts = new ManualThreadFactory();
    AtomicInteger closes = new AtomicInteger();
    Artifact artifact =
        new Artifact(
            outcome.equals("oversized") ? 17 : 1,
            Map.of(),
            new ArtifactBody() {
              @Override
              public InputStream openStream() {
                return InputStream.nullInputStream();
              }

              @Override
              public void close() throws IOException {
                closes.incrementAndGet();
                switch (closeFailure) {
                  case "io" -> throw new IOException("close failed");
                  case "runtime" -> throw new IllegalStateException("close failed");
                  case "error" -> throw new AssertionError("close failed");
                  default -> {}
                }
              }
            });
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            budget, Duration.ofSeconds(1), 8, System::nanoTime, workers, timeouts);
    try {
      var stage =
          executor.execute(
              decision(policy("p", budget), "key"),
              operation -> {
                if (outcome.equals("timeout")) {
                  timeouts.run(0);
                }
                if (outcome.equals("closed")) {
                  executor.close();
                }
                return artifact;
              });
      if (closeFailure.equals("error")) {
        assertThrows(AssertionError.class, () -> workers.run(0));
      } else {
        workers.run(0);
      }
      assertEquals(0, executor.activeJobs());
      assertEquals(0, executor.inFlightJobs());
      assertEquals(outcome.equals("success") ? 0 : 1, closes.get());
      switch (outcome) {
        case "success" -> assertSame(artifact, join(stage));
        case "timeout" -> assertEquals(OriginExecutionFailure.TIMEOUT, failure(stage).failure());
        case "closed" -> assertEquals(OriginExecutionFailure.CLOSED, failure(stage).failure());
        case "oversized" ->
            assertEquals(
                closeFailure.equals("error")
                    ? OriginExecutionFailure.MATERIALIZATION_FAILED
                    : OriginExecutionFailure.RESULT_TOO_LARGE,
                failure(stage).failure());
        default -> throw new AssertionError(outcome);
      }
    } finally {
      executor.close();
    }
  }
}
