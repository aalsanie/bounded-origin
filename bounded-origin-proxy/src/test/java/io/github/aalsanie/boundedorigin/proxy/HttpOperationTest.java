package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpOperationTest {
  @Test
  void roundTripsTheCompleteProducerAndBindsEveryFieldToIdentity() {
    HttpOperation base = operation(Map.of("accept", List.of("a", "b")));
    assertEquals(base, HttpOperation.from(base.operation()));
    String identity = identity(base);
    Map<String, List<String>> replacements =
        Map.of(
            "method",
            List.of("POST"),
            "host",
            List.of("other.test"),
            "target",
            List.of("/other?x=1"),
            "body-sha256",
            List.of("b".repeat(64)),
            "trust",
            List.of("TRUSTED"),
            "representation",
            List.of("PUBLIC"),
            "header:accept",
            List.of("present", "b", "a"));
    replacements.forEach(
        (field, values) -> {
          Map<String, List<String>> dimensions = new LinkedHashMap<>(base.operation().dimensions());
          dimensions.put(field, values);
          assertNotEquals(
              identity,
              HttpOperation.canonicalizer()
                  .canonicalize(new Operation(base.operation().type(), dimensions)),
              field);
        });
    assertNotEquals(
        identity(operation(Map.of())), identity(operation(Map.of("accept", List.of()))));
    assertNotEquals(
        identity(operation(Map.of("accept", List.of()))),
        identity(operation(Map.of("accept", List.of("")))));
    assertNotEquals(identity(operation(Map.of("accept", List.of("a")))), identity);
  }

  @Test
  void freezesHeadersAndNormalizesOnlyAuthorityCase() {
    List<String> values = new ArrayList<>(List.of("a", "b"));
    Map<String, List<String>> headers = new LinkedHashMap<>(Map.of("accept", values));
    HttpOperation operation = operation(headers);
    headers.clear();
    values.clear();
    assertEquals(Map.of("accept", List.of("a", "b")), operation.headers());
    assertThrows(UnsupportedOperationException.class, () -> operation.headers().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> operation.headers().get("accept").clear());
    assertEquals("example.test", operation.authority());
    assertEquals("/public?x=%41", operation.target());
    assertEquals(
        identity(operation),
        identity(
            new HttpOperation(
                "GET",
                "example.test",
                operation.target(),
                operation.bodySha256(),
                operation.trust(),
                operation.representation(),
                operation.headers())));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "Accept",
        "bad name",
        "bad:name",
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
        "x-real-ip",
        "if-match",
        "if-custom",
        "x-bounded-origin-trust"
      })
  void rejectsProtocolAuthenticationAndReservedHeaderSelectors(String name) {
    assertThrows(IllegalArgumentException.class, () -> HttpOperation.validateHeaderName(name));
    assertThrows(IllegalArgumentException.class, () -> operation(Map.of(name, List.of("value"))));
  }

  @Test
  void rejectsMalformedTargetsValuesDigestsAndAmbiguousOperationShapes() {
    HttpOperation base = operation(Map.of());
    for (String target :
        List.of("http://attacker.test/", "/%GG", "/a%2fb", "/%2e%2e/", "/a\\b", "*")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new HttpOperation(
                  "GET",
                  "example.test",
                  target,
                  base.bodySha256(),
                  base.trust(),
                  base.representation(),
                  Map.of()));
    }
    for (String digest : List.of("", "a".repeat(63), "G".repeat(64), "A".repeat(64))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new HttpOperation(
                  "GET",
                  "example.test",
                  "/",
                  digest,
                  base.trust(),
                  base.representation(),
                  Map.of()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> operation(Map.of("accept", List.of("value\r\nInjected: x"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpOperation.from(new Operation("http.request", base.operation().dimensions())));
    Map<String, List<String>> dimensions = new LinkedHashMap<>(base.operation().dimensions());
    dimensions.put("unknown", List.of("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpOperation.from(new Operation(base.operation().type(), dimensions)));
    dimensions.remove("unknown");
    for (String name : List.copyOf(dimensions.keySet())) {
      List<String> original = dimensions.remove(name);
      assertThrows(
          IllegalArgumentException.class,
          () -> HttpOperation.from(new Operation(base.operation().type(), dimensions)),
          name);
      dimensions.put(name, List.of("a", "b"));
      assertThrows(
          IllegalArgumentException.class,
          () -> HttpOperation.from(new Operation(base.operation().type(), dimensions)),
          name);
      dimensions.put(name, original);
    }
    dimensions.put("trust", List.of("SPOOFED"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpOperation.from(new Operation(base.operation().type(), dimensions)));
    dimensions.put("trust", List.of("UNTRUSTED"));
    for (List<String> value :
        List.of(List.of("present"), List.of("wrong"), List.of("absent", "wrong"))) {
      dimensions.put("header:accept", value);
      assertThrows(
          IllegalArgumentException.class,
          () -> HttpOperation.from(new Operation(base.operation().type(), dimensions)));
    }
  }

  private static HttpOperation operation(Map<String, List<String>> headers) {
    return new HttpOperation(
        "GET",
        "EXAMPLE.test",
        "/public?x=%41",
        "a".repeat(64),
        TrustLevel.UNTRUSTED,
        RepresentationContract.PUBLIC_IMMUTABLE,
        headers);
  }

  private static String identity(HttpOperation operation) {
    return HttpOperation.canonicalizer().canonicalize(operation.operation());
  }
}
