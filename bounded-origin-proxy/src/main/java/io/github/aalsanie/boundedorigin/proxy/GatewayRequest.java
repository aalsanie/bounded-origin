package io.github.aalsanie.boundedorigin.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record GatewayRequest(
    HttpRequestSecurity.ValidatedRequest validated,
    Map<String, List<String>> originHeaders,
    StreamingSpool.Result body) {
  GatewayRequest {
    validated = Objects.requireNonNull(validated, "validated");
    Objects.requireNonNull(originHeaders, "originHeaders");
    Map<String, List<String>> copy = new LinkedHashMap<>();
    originHeaders.forEach(
        (name, values) -> {
          Objects.requireNonNull(name, "origin header name");
          Objects.requireNonNull(values, "origin header values");
          copy.put(name, List.copyOf(new ArrayList<>(values)));
        });
    originHeaders = Map.copyOf(copy);
    body = Objects.requireNonNull(body, "body");
  }

  @Override
  public Map<String, List<String>> originHeaders() {
    return Map.copyOf(originHeaders);
  }
}
