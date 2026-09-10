package io.github.aalsanie.boundedorigin.testinfra;

import java.util.Locale;

public final class BuildBaselineProbe {
  private BuildBaselineProbe() {}

  public static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return "empty";
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
