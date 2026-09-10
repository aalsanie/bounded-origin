package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class MaterializationExceptionTest {
  @Test
  void preservesMessageAndCause() {
    IOException cause = new IOException("origin failed");

    MaterializationException withCause =
        new MaterializationException("materialization failed", cause);
    MaterializationException withoutCause = new MaterializationException("materialization failed");

    assertEquals("materialization failed", withCause.getMessage());
    assertSame(cause, withCause.getCause());
    assertEquals("materialization failed", withoutCause.getMessage());
  }
}
