package io.github.aalsanie.boundedorigin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ClientComputation(String type, String version, Map<String, String> parameters) {
  public ClientComputation {
    type = requireNonBlank(type, "type");
    version = requireNonBlank(version, "version");
    parameters = immutableParameters(parameters);
  }

  private static Map<String, String> immutableParameters(Map<String, String> values) {
    Objects.requireNonNull(values, "parameters");
    Map<String, String> copy = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          String validatedKey = requireNonBlank(key, "parameter key");
          Objects.requireNonNull(value, "parameter value");
          copy.put(validatedKey, value);
        });
    return Collections.unmodifiableMap(copy);
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
