package io.github.aalsanie.boundedorigin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RequestDescriptor(
    String name, Map<String, List<String>> attributes, TrustLevel trustLevel) {
  public RequestDescriptor {
    name = requireNonBlank(name, "name");
    attributes = immutableMultiMap(attributes, "attributes");
    trustLevel = Objects.requireNonNull(trustLevel, "trustLevel");
  }

  private static Map<String, List<String>> immutableMultiMap(
      Map<String, List<String>> values, String label) {
    Objects.requireNonNull(values, label);
    Map<String, List<String>> copy = new LinkedHashMap<>();
    values.forEach(
        (key, entries) -> {
          String validatedKey = requireNonBlank(key, label + " key");
          Objects.requireNonNull(entries, label + " values");
          if (entries.isEmpty()) {
            throw new IllegalArgumentException(label + " values must not be empty");
          }
          List<String> entryCopy = List.copyOf(entries);
          copy.put(validatedKey, entryCopy);
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
