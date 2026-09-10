package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BudgetTest {
  @Test
  void acceptsBoundedValues() {
    Budget budget = new Budget(4, 32, Duration.ofSeconds(5), 1_048_576);
    assertEquals(4, budget.maxActive());
    assertEquals(32, budget.maxQueued());
    assertEquals(Duration.ofSeconds(5), budget.timeout());
    assertEquals(1_048_576, budget.maxResultBytes());
  }

  @Test
  void rejectsUnboundedOrInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new Budget(0, 1, Duration.ofSeconds(1), 1));
    assertThrows(IllegalArgumentException.class, () -> new Budget(-1, 1, Duration.ofSeconds(1), 1));
    assertThrows(IllegalArgumentException.class, () -> new Budget(1, -1, Duration.ofSeconds(1), 1));
    assertThrows(NullPointerException.class, () -> new Budget(1, 0, null, 1));
    assertThrows(IllegalArgumentException.class, () -> new Budget(1, 0, Duration.ZERO, 1));
    assertThrows(IllegalArgumentException.class, () -> new Budget(1, 0, Duration.ofNanos(-1), 1));
    assertThrows(IllegalArgumentException.class, () -> new Budget(1, 0, Duration.ofSeconds(1), 0));
    assertThrows(IllegalArgumentException.class, () -> new Budget(1, 0, Duration.ofSeconds(1), -1));
  }
}
