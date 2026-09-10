package io.github.aalsanie.boundedorigin.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Canonicalizers {
  private Canonicalizers() {}

  public static Canonicalizer byDimensions(String... dimensions) {
    Objects.requireNonNull(dimensions, "dimensions");
    return byDimensions(List.of(dimensions));
  }

  public static Canonicalizer byDimensions(List<String> dimensions) {
    Objects.requireNonNull(dimensions, "dimensions");
    List<String> selected = validatedDimensions(dimensions);
    return operation -> canonicalize(operation, selected);
  }

  private static List<String> validatedDimensions(List<String> dimensions) {
    List<String> copy = new ArrayList<>(dimensions.size());
    Set<String> seen = new HashSet<>();
    for (String dimension : dimensions) {
      Objects.requireNonNull(dimension, "dimension");
      if (dimension.isBlank()) {
        throw new IllegalArgumentException("dimension must not be blank");
      }
      if (!seen.add(dimension)) {
        throw new IllegalArgumentException("duplicate dimension " + dimension);
      }
      copy.add(dimension);
    }
    return List.copyOf(copy);
  }

  private static String canonicalize(Operation operation, List<String> dimensions) {
    Objects.requireNonNull(operation, "operation");
    StringBuilder result = new StringBuilder();
    append(result, operation.type());
    for (String dimension : dimensions) {
      append(result, dimension);
      List<String> values = operation.dimensions().get(dimension);
      if (values == null) {
        result.append('0');
        continue;
      }
      result.append('1').append(values.size()).append(':');
      for (String value : values) {
        append(result, value);
      }
    }
    return result.toString();
  }

  private static void append(StringBuilder target, String value) {
    target.append(value.length()).append(':').append(value);
  }
}
