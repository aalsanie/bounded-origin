package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RepresentationBoundary {
  private static final Set<String> UNSUPPORTED_REQUEST =
      Set.of(
          "authorization",
          "cookie",
          "cookie2",
          "proxy-authorization",
          "range",
          "cache-control",
          "pragma");
  private static final Set<String> UNSHAREABLE_RESPONSE =
      Set.of(
          "set-cookie",
          "set-cookie2",
          "www-authenticate",
          "proxy-authenticate",
          "authentication-info",
          "proxy-authentication-info",
          "content-range",
          "expires",
          "age");

  private RepresentationBoundary() {}

  static HttpOperation operation(OriginDecision.Selected selected, GatewayRequest request) {
    HttpOperation operation = HttpOperation.from(selected.operation());
    if (!HttpOperation.canonicalizer()
        .canonicalize(selected.operation())
        .equals(selected.operationKey().semanticIdentity())) {
      throw new IllegalArgumentException("HTTP policy identity omits producer inputs");
    }
    if (!operation.bodySha256().equals(request.body().sha256())) {
      throw new IllegalArgumentException("HTTP operation does not identify the received body");
    }
    if (selected.policy().strategy() != ExecutionStrategy.BOUNDED_COMPUTE
        && operation.representation() != RepresentationContract.PUBLIC_IMMUTABLE) {
      throw new IllegalArgumentException("artifact reuse requires PUBLIC_IMMUTABLE");
    }
    for (String header : request.originHeaders().keySet()) {
      if (UNSUPPORTED_REQUEST.contains(header) || header.startsWith("if-")) {
        throw new UnsupportedRequestException();
      }
      if (("content-type".equals(header) || "content-encoding".equals(header))
          && !request.originHeaders().get(header).equals(operation.headers().get(header))) {
        throw new UnsupportedRequestException();
      }
    }
    return operation;
  }

  static Map<String, List<String>> producerHeaders(HttpOperation operation) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    operation
        .headers()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                result.put(name, values);
              }
            });
    result.put("host", List.of(operation.authority()));
    return Map.copyOf(result);
  }

  static void response(HttpOperation operation, Artifact artifact, Map<String, String> metadata) {
    if (artifact.statusCode() == 206 || artifact.statusCode() == 304) {
      throw new IllegalArgumentException(
          "partial and conditional responses cannot be shared as complete representations");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    metadata.forEach(
        (name, value) -> {
          if (headers.putIfAbsent(name.toLowerCase(Locale.ROOT), value) != null) {
            throw new IllegalArgumentException("ambiguous representation metadata");
          }
        });
    for (String name : UNSHAREABLE_RESPONSE) {
      if (headers.containsKey(name)) {
        throw new IllegalArgumentException("origin response is not eligible for public sharing");
      }
    }
    // This is artifact publication, not a revalidating HTTP cache. Do not silently ignore freshness
    // or authorization requirements, including directives unfamiliar to this implementation.
    boolean publicResponse = false;
    for (String token : headers.getOrDefault("cache-control", "").split(",", -1)) {
      String directive = token.trim().toLowerCase(Locale.ROOT);
      if ("public".equals(directive)) {
        publicResponse = true;
      } else if (!"no-transform".equals(directive)) {
        throw new IllegalArgumentException("unsupported origin response cache directive");
      }
    }
    if (!publicResponse || headers.containsKey("pragma") || headers.containsKey("trailer")) {
      throw new IllegalArgumentException("origin response does not affirm public sharing");
    }
    if (headers.containsKey("vary")) {
      for (String token : headers.get("vary").split(",", -1)) {
        String name = token.trim().toLowerCase(Locale.ROOT);
        if (!"host".equals(name) && !operation.headers().containsKey(name)) {
          throw new IllegalArgumentException("origin varies on an unselected representation input");
        }
      }
    }
    for (String token : headers.getOrDefault("connection", "").split(",", -1)) {
      String name = token.trim().toLowerCase(Locale.ROOT);
      if ("cache-control".equals(name) || "vary".equals(name)) {
        throw new IllegalArgumentException("connection header hides representation constraints");
      }
    }
  }

  static final class UnsupportedRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnsupportedRequestException() {
      super("request requires unsupported caller-specific representation semantics");
    }
  }
}
