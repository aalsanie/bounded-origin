package io.github.aalsanie.boundedorigin.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record OriginRequest(
    String method,
    String target,
    Map<String, List<String>> headers,
    StreamingSpool.Result body,
    long maxResponseBytes) {
  OriginRequest {
    method = requireNonBlank(method, "method");
    target = requireNonBlank(target, "target");
    Objects.requireNonNull(headers, "headers");
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          String validated = requireNonBlank(name, "header name");
          Objects.requireNonNull(values, "header values");
          copy.put(validated, List.copyOf(values));
        });
    headers = Map.copyOf(copy);
    body = Objects.requireNonNull(body, "body");
    if (maxResponseBytes <= 0) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
  }

  @Override
  public Map<String, List<String>> headers() {
    return Map.copyOf(headers);
  }

  long bodyLength() {
    return body.length();
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
