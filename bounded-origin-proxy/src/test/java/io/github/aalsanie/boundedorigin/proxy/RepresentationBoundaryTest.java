package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepresentationBoundaryTest {
  private static final HttpOperation OPERATION =
      new HttpOperation(
          "GET",
          "example.test",
          "/",
          "a".repeat(64),
          TrustLevel.UNTRUSTED,
          RepresentationContract.PUBLIC_IMMUTABLE,
          Map.of("accept", List.of()));

  @Test
  void selectedAbsenceIsNotForwardedButIsCoveredByVary() {
    assertEquals(
        Map.of("host", List.of("example.test")), RepresentationBoundary.producerHeaders(OPERATION));
    HttpOperation present =
        new HttpOperation(
            "POST",
            "example.test",
            "/",
            "a".repeat(64),
            TrustLevel.UNTRUSTED,
            RepresentationContract.PUBLIC,
            Map.of("content-type", List.of("text/plain")));
    assertEquals(
        Map.of("host", List.of("example.test"), "content-type", List.of("text/plain")),
        RepresentationBoundary.producerHeaders(present));
    assertDoesNotThrow(
        () -> check(200, Map.of("Cache-Control", "Public, no-transform", "Vary", "Accept, Host")));
    assertDoesNotThrow(() -> check(204, Map.of("cache-control", "public")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "private",
        "public, private",
        "public, private=\"x-name\"",
        "no-store",
        "public, no-store",
        "public, no-cache",
        "public, max-age=1",
        "public, s-maxage=0",
        "public, must-revalidate",
        "public, immutable",
        "public, extension",
        "public,",
        "no-transform"
      })
  void rejectsMissingPublicConsentAndUnsupportedCacheRequirements(String value) {
    assertThrows(IllegalArgumentException.class, () -> check(200, Map.of("cache-control", value)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "set-cookie",
        "set-cookie2",
        "www-authenticate",
        "proxy-authenticate",
        "authentication-info",
        "proxy-authentication-info",
        "content-range",
        "expires",
        "age",
        "pragma",
        "trailer"
      })
  void rejectsOtherRepresentationConstraintsEvenWhenMarkedPublic(String header) {
    assertThrows(
        IllegalArgumentException.class,
        () -> check(200, Map.of("cache-control", "public", header, "synthetic")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "", "accept,", "x-account", "authorization", "accept, unknown"})
  void rejectsUnrepresentedVariation(String vary) {
    assertThrows(
        IllegalArgumentException.class,
        () -> check(200, Map.of("cache-control", "public", "vary", vary)));
  }

  @Test
  void rejectsPartialAndConditionalStatusesAndAmbiguousHeaders() {
    for (int status : List.of(206, 304)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> check(status, Map.of("cache-control", "public")),
          Integer.toString(status));
    }
    for (int status : List.of(201, 203, 205, 404, 500, 599)) {
      assertDoesNotThrow(() -> check(status, Map.of("cache-control", "public")));
    }
    for (String connection : List.of("Cache-Control", "keep-alive, Vary")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> check(200, Map.of("cache-control", "public", "connection", connection)));
    }
    assertDoesNotThrow(
        () -> check(200, Map.of("cache-control", "public", "connection", "keep-alive")));
    Map<String, String> duplicate = new LinkedHashMap<>();
    duplicate.put("cache-control", "public");
    duplicate.put("Cache-Control", "private");
    assertThrows(IllegalArgumentException.class, () -> check(200, duplicate));
    assertThrows(IllegalArgumentException.class, () -> check(200, Map.of()));
  }

  private static void check(int status, Map<String, String> headers) {
    RepresentationBoundary.response(
        OPERATION, new Artifact(status, 0, Map.of(), InputStream::nullInputStream), headers);
  }
}
