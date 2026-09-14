package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OriginExecutorMetricsTest {
  @Test
  void snapshotReflectsEmptyExecutor() {
    Budget budget = new Budget(1, 1, Duration.ofSeconds(1), 1024);
    try (BoundedOriginExecutor executor =
        new BoundedOriginExecutor(budget, Duration.ofSeconds(1), 8)) {
      assertEquals(new OriginExecutorStats(0, 0, 0, 0), OriginExecutorMetrics.snapshot(executor));
    }
  }

  @Test
  void statsRejectNegativeOrImpossibleValues() {
    assertThrows(IllegalArgumentException.class, () -> new OriginExecutorStats(-1, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new OriginExecutorStats(0, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new OriginExecutorStats(0, 0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new OriginExecutorStats(0, 0, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> new OriginExecutorStats(1, 1, 1, 0));
  }

  @Test
  void snapshotRejectsNullExecutor() {
    assertThrows(NullPointerException.class, () -> OriginExecutorMetrics.snapshot(null));
  }
}
