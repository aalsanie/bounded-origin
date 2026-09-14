package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructuredLogTest {
  @Test
  void escapesJsonControlCharacters() {
    assertEquals("a\\\"b\\\\c\\n\\r\\t\\u0001z", StructuredLog.escape("a\"b\\c\n\r\t\u0001z"));
    assertEquals("plain", StructuredLog.escape("plain"));
  }

  @Test
  void logEntryMethodsAcceptUntrustedFields() {
    StructuredLog.started("listen\n", "admin\"", "origin\\");
    StructuredLog.request(1, "G\nET", "/x\r", "p\t", 200, 1_500_000);
    StructuredLog.failure(1, "bad\"event", new IllegalStateException("ignored"));
    StructuredLog.stopped();
  }
}
