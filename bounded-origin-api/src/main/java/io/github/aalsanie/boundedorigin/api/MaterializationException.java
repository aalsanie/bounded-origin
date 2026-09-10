package io.github.aalsanie.boundedorigin.api;

public final class MaterializationException extends Exception {
  private static final long serialVersionUID = 1L;

  public MaterializationException(String message) {
    super(message);
  }

  public MaterializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
