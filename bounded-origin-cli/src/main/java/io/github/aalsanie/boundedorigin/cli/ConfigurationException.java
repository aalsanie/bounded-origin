package io.github.aalsanie.boundedorigin.cli;

final class ConfigurationException extends Exception {
  private static final long serialVersionUID = 1L;

  ConfigurationException(String message) {
    super(message);
  }

  ConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
