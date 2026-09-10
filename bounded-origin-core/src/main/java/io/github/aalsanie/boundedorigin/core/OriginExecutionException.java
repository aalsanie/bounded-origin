package io.github.aalsanie.boundedorigin.core;

import java.util.Objects;

public final class OriginExecutionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final OriginExecutionFailure failure;

  OriginExecutionException(OriginExecutionFailure failure, String message) {
    super(message);
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  OriginExecutionException(OriginExecutionFailure failure, String message, Throwable cause) {
    super(message, cause);
    this.failure = Objects.requireNonNull(failure, "failure");
  }

  public OriginExecutionFailure failure() {
    return failure;
  }
}
