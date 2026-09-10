package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OriginExecutionExceptionTest {
  @Test
  void preservesFailureMessageAndCause() {
    IllegalStateException cause = new IllegalStateException("cause");
    OriginExecutionException withoutCause =
        new OriginExecutionException(OriginExecutionFailure.CLOSED, "closed");
    OriginExecutionException withCause =
        new OriginExecutionException(
            OriginExecutionFailure.MATERIALIZATION_FAILED, "failed", cause);

    assertEquals(OriginExecutionFailure.CLOSED, withoutCause.failure());
    assertEquals("closed", withoutCause.getMessage());
    assertEquals(OriginExecutionFailure.MATERIALIZATION_FAILED, withCause.failure());
    assertEquals("failed", withCause.getMessage());
    assertSame(cause, withCause.getCause());
    assertThrows(
        NullPointerException.class,
        () -> assertNotNull(new OriginExecutionException(null, "message")));
    assertThrows(
        NullPointerException.class,
        () -> assertNotNull(new OriginExecutionException(null, "message", cause)));
  }
}
