package io.github.aalsanie.boundedorigin.proxy;

import java.lang.System.Logger.Level;

final class StructuredLog {
  private static final System.Logger LOGGER = System.getLogger("bounded-origin-proxy");

  private StructuredLog() {}

  static void started(String listen, String admin, String origin) {
    LOGGER.log(
        Level.INFO,
        "{\"event\":\"gateway_started\",\"listen\":\""
            + escape(listen)
            + "\",\"admin\":\""
            + escape(admin)
            + "\",\"origin\":\""
            + escape(origin)
            + "\"}");
  }

  static void stopped() {
    LOGGER.log(Level.INFO, "{\"event\":\"gateway_stopped\"}");
  }

  static void request(
      long requestId, String method, String path, String policyId, int status, long durationNanos) {
    LOGGER.log(
        Level.INFO,
        "{\"event\":\"request\",\"request_id\":"
            + requestId
            + ",\"method\":\""
            + escape(method)
            + "\",\"path\":\""
            + escape(path)
            + "\",\"policy\":\""
            + escape(policyId)
            + "\",\"status\":"
            + status
            + ",\"duration_ms\":"
            + (durationNanos / 1_000_000.0d)
            + '}');
  }

  static void failure(long requestId, String event, Throwable throwable) {
    LOGGER.log(
        Level.WARNING,
        "{\"event\":\""
            + escape(event)
            + "\",\"request_id\":"
            + requestId
            + ",\"error\":\""
            + escape(throwable.getClass().getName())
            + "\"}");
  }

  static String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append("\\u").append(String.format("%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
