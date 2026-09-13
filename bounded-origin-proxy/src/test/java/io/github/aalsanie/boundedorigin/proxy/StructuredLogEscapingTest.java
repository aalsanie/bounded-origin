package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructuredLogEscapingTest {
  @Test
  void escapesJsonSpecialAndControlCharacters() {
    String input = "\"\\\n\r\t\b\f\u0001AZ";

    assertEquals("\\\"\\\\\\n\\r\\t\\u0008\\u000c\\u0001AZ", StructuredLog.escape(input));
  }
}
