package io.github.aalsanie.boundedorigin.api;

import java.time.Duration;
import java.util.Objects;

public record Budget(int maxActive, int maxQueued, Duration timeout, long maxResultBytes) {
  public Budget {
    if (maxActive <= 0) {
      throw new IllegalArgumentException("maxActive must be positive");
    }
    if (maxQueued < 0) {
      throw new IllegalArgumentException("maxQueued must be non-negative");
    }
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (maxResultBytes <= 0) {
      throw new IllegalArgumentException("maxResultBytes must be positive");
    }
  }
}
