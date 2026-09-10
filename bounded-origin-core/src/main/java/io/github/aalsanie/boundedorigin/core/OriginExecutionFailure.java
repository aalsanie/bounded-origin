package io.github.aalsanie.boundedorigin.core;

public enum OriginExecutionFailure {
  GLOBAL_QUEUE_LIMIT,
  POLICY_QUEUE_LIMIT,
  TIMEOUT,
  RESULT_TOO_LARGE,
  MATERIALIZATION_FAILED,
  COOLDOWN,
  CLOSED,
  INTERNAL_ERROR
}
