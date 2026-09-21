package io.github.aalsanie.boundedorigin.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GatewayConfigurationValidator {
  private static final Set<String> HOST_KEYS = Set.of("listen.host", "admin.host", "origin.host");
  private static final Set<String> ALLOW_ZERO_PORT_KEYS = Set.of("listen.port", "admin.port");
  private static final Set<String> POSITIVE_INT_KEYS =
      Set.of(
          "event-loop.threads",
          "origin.event-loop.threads",
          "frontend.max-connections",
          "server.backlog",
          "http.max-initial-line-bytes",
          "http.max-header-bytes",
          "http.chunk-bytes",
          "spool.max-files",
          "origin.max-connections",
          "origin.max-active",
          "origin.max-cooldown-entries",
          "overload.retry-after-seconds",
          "downstream.write-low-watermark-bytes",
          "downstream.write-high-watermark-bytes");
  private static final Set<String> NON_NEGATIVE_INT_KEYS =
      Set.of("origin.max-pending-acquires", "origin.max-queued");
  private static final Set<String> POSITIVE_LONG_KEYS =
      Set.of("spool.max-bytes", "origin.max-result-bytes");
  private static final Set<String> NON_NEGATIVE_LONG_KEYS =
      Set.of("http.max-request-body-bytes", "http.chunked-response-threshold-bytes");
  private static final Set<String> POSITIVE_DURATION_KEYS =
      Set.of(
          "origin.acquire-timeout",
          "origin.max-execution-duration",
          "origin.connect-timeout",
          "origin.response-timeout",
          "origin.failure-cooldown",
          "request.timeout",
          "idle.timeout",
          "drain.timeout");

  private GatewayConfigurationValidator() {}

  static void validate(Map<String, String> values) throws ConfigurationException {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      String path = "configuration.gateway." + key;
      if (HOST_KEYS.contains(key)) {
        host(value, path);
      } else if (ALLOW_ZERO_PORT_KEYS.contains(key)) {
        port(value, path, true);
      } else if ("origin.port".equals(key)) {
        port(value, path, false);
      } else if ("temporary.directory".equals(key) || "origin.ownership-directory".equals(key)) {
        path(value, path);
      } else if ("origin.completion-contract".equals(key)) {
        if (!"DISABLED".equals(value) && !"RESPONSE_COMPLETE".equals(value)) {
          throw new ConfigurationException(path + " must be DISABLED or RESPONSE_COMPLETE");
        }
      } else if ("ingress.trust".equals(key)) {
        trust(value, path);
      } else if ("forwarded.trust".equals(key)) {
        booleanValue(value, path);
      } else if (POSITIVE_INT_KEYS.contains(key)) {
        positiveInt(value, path);
      } else if (NON_NEGATIVE_INT_KEYS.contains(key)) {
        nonNegativeInt(value, path);
      } else if (POSITIVE_LONG_KEYS.contains(key)) {
        positiveLong(value, path);
      } else if (NON_NEGATIVE_LONG_KEYS.contains(key)) {
        nonNegativeLong(value, path);
      } else if (POSITIVE_DURATION_KEYS.contains(key)) {
        positiveDuration(value, path);
      } else {
        throw new ConfigurationException("unsupported gateway configuration key " + key);
      }
    }
  }

  private static void host(String value, String path) throws ConfigurationException {
    if (value.isBlank()) {
      throw new ConfigurationException(path + " must not be blank");
    }
    if (value
        .chars()
        .anyMatch(
            character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
      throw new ConfigurationException(path + " contains invalid characters");
    }
  }

  private static void path(String value, String path) throws ConfigurationException {
    if (value.isBlank()) {
      throw new ConfigurationException(path + " must not be blank");
    }
    try {
      Path.of(value);
    } catch (InvalidPathException exception) {
      throw new ConfigurationException(path + " is not a valid filesystem path", exception);
    }
  }

  private static void port(String value, String path, boolean allowZero)
      throws ConfigurationException {
    int parsed = integer(value, path);
    int minimum = allowZero ? 0 : 1;
    if (parsed < minimum || parsed > 65_535) {
      throw new ConfigurationException(path + " must be between " + minimum + " and 65535");
    }
  }

  private static void positiveInt(String value, String path) throws ConfigurationException {
    if (integer(value, path) <= 0) {
      throw new ConfigurationException(path + " must be positive");
    }
  }

  private static void nonNegativeInt(String value, String path) throws ConfigurationException {
    if (integer(value, path) < 0) {
      throw new ConfigurationException(path + " must be non-negative");
    }
  }

  private static int integer(String value, String path) throws ConfigurationException {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new ConfigurationException(path + " must be an integer", exception);
    }
  }

  private static void positiveLong(String value, String path) throws ConfigurationException {
    if (longValue(value, path) <= 0) {
      throw new ConfigurationException(path + " must be positive");
    }
  }

  private static void nonNegativeLong(String value, String path) throws ConfigurationException {
    if (longValue(value, path) < 0) {
      throw new ConfigurationException(path + " must be non-negative");
    }
  }

  private static long longValue(String value, String path) throws ConfigurationException {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new ConfigurationException(path + " must be a long integer", exception);
    }
  }

  private static void positiveDuration(String value, String path) throws ConfigurationException {
    try {
      Duration duration = Duration.parse(value);
      if (duration.isZero() || duration.isNegative()) {
        throw new ConfigurationException(path + " must be a positive duration");
      }
      try {
        long nanos = duration.toNanos();
        if (nanos <= 0) {
          throw new ConfigurationException(path + " must be at least one nanosecond");
        }
      } catch (ArithmeticException exception) {
        throw new ConfigurationException(path + " is too large", exception);
      }
    } catch (DateTimeParseException exception) {
      throw new ConfigurationException(path + " must be an ISO-8601 duration", exception);
    }
  }

  private static void booleanValue(String value, String path) throws ConfigurationException {
    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
      throw new ConfigurationException(path + " must be true or false");
    }
  }

  private static void trust(String value, String path) throws ConfigurationException {
    String normalized = value.toUpperCase(Locale.ROOT);
    if (!"TRUSTED".equals(normalized) && !"UNTRUSTED".equals(normalized)) {
      throw new ConfigurationException(path + " must be TRUSTED or UNTRUSTED");
    }
  }
}
