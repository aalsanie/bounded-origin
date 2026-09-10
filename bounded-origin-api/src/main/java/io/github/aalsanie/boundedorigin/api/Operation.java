package io.github.aalsanie.boundedorigin.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Operation(String type, Map<String, List<String>> dimensions) {
  public Operation {
    type = requireNonBlank(type, "type");
    dimensions = immutableMultiMap(dimensions);
  }

  @Override
  public Map<String, List<String>> dimensions() {
    return Map.copyOf(dimensions);
  }

  private static Map<String, List<String>> immutableMultiMap(Map<String, List<String>> values) {
    Objects.requireNonNull(values, "dimensions");
    Map<String, List<String>> copy = new LinkedHashMap<>();
    values.forEach(
        (key, entries) -> {
          String validatedKey = requireNonBlank(key, "dimension key");
          Objects.requireNonNull(entries, "dimension values");
          if (entries.isEmpty()) {
            throw new IllegalArgumentException("dimension values must not be empty");
          }
          copy.put(validatedKey, List.copyOf(entries));
        });
    return Map.copyOf(copy);
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
