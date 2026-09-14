package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class StructuredLogEmissionTest {
  private static final Logger LOGGER = Logger.getLogger("bounded-origin-proxy");

  @Test
  void structuredEventsAreActuallyEmittedWithExactFields() {
    Logger logger = LOGGER;
    CapturingHandler handler = new CapturingHandler();
    boolean previousParentHandlers = logger.getUseParentHandlers();
    Level previousLevel = logger.getLevel();

    logger.setUseParentHandlers(false);
    logger.setLevel(Level.ALL);
    handler.setLevel(Level.ALL);
    logger.addHandler(handler);
    try {
      StructuredLog.started("listen\n", "admin\"", "origin\\");
      StructuredLog.request(7, "G\nET", "/x\r", "p\t", 201, 1_500_000);
      StructuredLog.failure(8, "bad\"event", new IllegalStateException("ignored"));
      StructuredLog.stopped();
    } finally {
      logger.removeHandler(handler);
      logger.setUseParentHandlers(previousParentHandlers);
      logger.setLevel(previousLevel);
    }

    List<LogRecord> records = handler.records();
    assertEquals(4, records.size());
    assertEquals(Level.INFO, records.get(0).getLevel());
    assertEquals(
        "{\"event\":\"gateway_started\",\"listen\":\"listen\\n\",\"admin\":\"admin\\\"\",\"origin\":\"origin\\\\\"}",
        records.get(0).getMessage());

    assertEquals(Level.INFO, records.get(1).getLevel());
    assertEquals(
        "{\"event\":\"request\",\"request_id\":7,\"method\":\"G\\nET\",\"path\":\"/x\\r\",\"policy\":\"p\\t\",\"status\":201,\"duration_ms\":1.5}",
        records.get(1).getMessage());

    assertEquals(Level.WARNING, records.get(2).getLevel());
    assertEquals(
        "{\"event\":\"bad\\\"event\",\"request_id\":8,\"error\":\"java.lang.IllegalStateException\"}",
        records.get(2).getMessage());

    assertEquals(Level.INFO, records.get(3).getLevel());
    assertEquals("{\"event\":\"gateway_stopped\"}", records.get(3).getMessage());
    assertTrue(records.stream().allMatch(record -> record.getMessage().startsWith("{")));
  }

  private static final class CapturingHandler extends Handler {
    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    private List<LogRecord> records() {
      return List.copyOf(records);
    }
  }
}
