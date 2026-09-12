package io.github.aalsanie.boundedorigin.core;

import java.util.Objects;

public final class OriginExecutorMetrics {
  private OriginExecutorMetrics() {}

  public static OriginExecutorStats snapshot(BoundedOriginExecutor executor) {
    Objects.requireNonNull(executor, "executor");
    return executor.snapshotStats();
  }
}
