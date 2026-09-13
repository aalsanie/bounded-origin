package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ClientComputation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class ResponseArtifacts {
  private ResponseArtifacts() {}

  static Artifact text(int status, String text) {
    return text(status, text, Map.of());
  }

  static Artifact text(int status, String text, Map<String, String> extraHeaders) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(extraHeaders, "extraHeaders");
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    TreeMap<String, String> headers = new TreeMap<>();
    headers.put("content-type", "text/plain; charset=utf-8");
    headers.put("cache-control", "no-store");
    headers.putAll(extraHeaders);
    return new Artifact(status, body.length, headers, () -> new ByteArrayInputStream(body));
  }

  static Artifact clientComputation(ClientComputation computation) {
    Objects.requireNonNull(computation, "computation");
    StringBuilder json = new StringBuilder(128);
    json.append("{\"type\":\"")
        .append(json(computation.type()))
        .append("\",\"version\":\"")
        .append(json(computation.version()))
        .append("\",\"parameters\":{");
    boolean first = true;
    for (Map.Entry<String, String> entry : new TreeMap<>(computation.parameters()).entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"')
          .append(json(entry.getKey()))
          .append("\":\"")
          .append(json(entry.getValue()))
          .append('"');
    }
    json.append("}}");
    byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
    return new Artifact(
        200,
        body.length,
        Map.of("content-type", "application/json; charset=utf-8", "cache-control", "no-store"),
        () -> new ByteArrayInputStream(body));
  }

  private static String json(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 8);
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
