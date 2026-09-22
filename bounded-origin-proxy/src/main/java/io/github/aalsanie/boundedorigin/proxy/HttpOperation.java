package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A canonical, public producer request. Matchers must project semantic inputs into this request;
 * the gateway never forwards other inputs from the caller that happens to start a shared flight.
 * Selected header names are lowercase, with exact ordered values. An empty list selects an absent
 * header, distinct from a present header with an empty value. The body digest is checked against
 * the received body before any lookup or execution.
 */
public record HttpOperation(
    String method,
    String authority,
    String target,
    String bodySha256,
    TrustLevel trust,
    RepresentationContract representation,
    Map<String, List<String>> headers) {
  private static final String TYPE = "http.public-operation.v1";
  private static final String HEADER_PREFIX = "header:";
  private static final Set<String> FIELDS =
      Set.of("method", "host", "target", "body-sha256", "trust", "representation");
  private static final Set<String> FORBIDDEN_HEADERS =
      Set.of(
          "authorization",
          "cookie",
          "cookie2",
          "proxy-authorization",
          "host",
          "content-length",
          "connection",
          "keep-alive",
          "proxy-connection",
          "proxy-authenticate",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "expect",
          "range",
          "cache-control",
          "pragma",
          "forwarded",
          "x-forwarded-for",
          "x-forwarded-host",
          "x-forwarded-proto",
          "x-real-ip");

  public HttpOperation {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(authority, "authority");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(bodySha256, "bodySha256");
    Objects.requireNonNull(trust, "trust");
    Objects.requireNonNull(representation, "representation");
    Objects.requireNonNull(headers, "headers");
    var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), target);
    request.headers().set("host", authority);
    try {
      HttpRequestSecurity.validate(request, 0);
    } catch (HttpRequestSecurity.HttpContractException exception) {
      throw new IllegalArgumentException("invalid canonical HTTP request", exception);
    }
    authority = authority.toLowerCase(Locale.ROOT);
    if (!bodySha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("bodySha256 must be a lowercase SHA-256 digest");
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach(
        (name, values) -> {
          validateHeaderName(name);
          List<String> immutable = List.copyOf(values);
          immutable.forEach(value -> request.headers().add(name, value));
          copy.put(name, immutable);
        });
    headers = Map.copyOf(copy);
  }

  /** Validates names eligible for explicit representation selection; it does not grant trust. */
  public static void validateHeaderName(String name) {
    Objects.requireNonNull(name, "name");
    if (!name.equals(name.toLowerCase(Locale.ROOT))
        || !name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")
        || FORBIDDEN_HEADERS.contains(name)
        || name.startsWith("if-")
        || name.startsWith("x-bounded-origin-")) {
      throw new IllegalArgumentException("unsupported representation header " + name);
    }
  }

  public Operation operation() {
    Map<String, List<String>> dimensions = new LinkedHashMap<>();
    dimensions.put("method", List.of(method));
    dimensions.put("host", List.of(authority));
    dimensions.put("target", List.of(target));
    dimensions.put("body-sha256", List.of(bodySha256));
    dimensions.put("trust", List.of(trust.name()));
    dimensions.put("representation", List.of(representation.name()));
    headers.forEach(
        (name, values) -> {
          List<String> encoded = new ArrayList<>();
          encoded.add(values.isEmpty() ? "absent" : "present");
          encoded.addAll(values);
          dimensions.put(HEADER_PREFIX + name, encoded);
        });
    return new Operation(TYPE, dimensions);
  }

  /** The gateway requires this complete identity; partial/custom identities fail closed. */
  public static Canonicalizer canonicalizer() {
    return operation -> {
      HttpOperation parsed = from(operation);
      Operation canonical = parsed.operation();
      return Canonicalizers.byDimensions(canonical.dimensions().keySet().stream().sorted().toList())
          .canonicalize(canonical);
    };
  }

  public static HttpOperation from(Operation operation) {
    Objects.requireNonNull(operation, "operation");
    if (!TYPE.equals(operation.type())) {
      throw new IllegalArgumentException("HTTP sharing requires an explicit HttpOperation");
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    operation
        .dimensions()
        .forEach(
            (name, values) -> {
              if (name.startsWith(HEADER_PREFIX)) {
                List<String> decoded;
                if (values.size() == 1 && "absent".equals(values.getFirst())) {
                  decoded = List.of();
                } else if (values.size() > 1 && "present".equals(values.getFirst())) {
                  decoded = values.subList(1, values.size());
                } else {
                  throw new IllegalArgumentException("malformed HTTP header dimension");
                }
                headers.put(name.substring(HEADER_PREFIX.length()), decoded);
              } else if (!FIELDS.contains(name)) {
                throw new IllegalArgumentException("unsupported HTTP operation dimension " + name);
              }
            });
    return new HttpOperation(
        single(operation, "method"),
        single(operation, "host"),
        single(operation, "target"),
        single(operation, "body-sha256"),
        TrustLevel.valueOf(single(operation, "trust")),
        RepresentationContract.valueOf(single(operation, "representation")),
        headers);
  }

  private static String single(Operation operation, String name) {
    List<String> values = operation.dimensions().get(name);
    if (values == null || values.size() != 1) {
      throw new IllegalArgumentException(name + " must have exactly one value");
    }
    return values.getFirst();
  }
}
