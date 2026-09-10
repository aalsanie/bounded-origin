package io.github.aalsanie.boundedorigin.testinfra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildBaselineProbeTest {
  @Test
  void normalizesNull() {
    assertEquals("empty", BuildBaselineProbe.normalize(null));
  }

  @Test
  void normalizesBlank() {
    assertEquals("empty", BuildBaselineProbe.normalize("   "));
  }

  @Test
  void normalizesValue() {
    assertEquals("bounded origin", BuildBaselineProbe.normalize("  BOUNDED ORIGIN  "));
  }
}
