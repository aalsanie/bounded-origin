package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpRequestSecurityBoundaryTest {
  @Test
  void validationRejectsDecoderFailureInvalidLimitsAndAmbiguousTargets() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpRequestSecurity.validate(request(HttpMethod.GET, "/", "example.test"), -1));

    HttpRequest decoderFailure = request(HttpMethod.GET, "/", "example.test");
    decoderFailure.setDecoderResult(DecoderResult.failure(new IOException("bad request")));
    assertStatus(400, decoderFailure, 0);

    assertStatus(400, request(HttpMethod.GET, "", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/a\\b", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/bad%", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/bad%G0", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/bad%0G", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/bad\u007f", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/./x", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/%2E/x", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/%2e%2E/x", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/a/%5C/b", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/a/%00/b", "example.test"), 0);
  }

  @Test
  void hostValidationCoversIpv6PortsAndRegNameBoundaries() {
    assertEquals(
        "EXAMPLE-test9.local:443",
        HttpRequestSecurity.validate(request(HttpMethod.GET, "/", "EXAMPLE-test9.local:443"), 0)
            .host());
    assertEquals(
        "[::1]", HttpRequestSecurity.validate(request(HttpMethod.GET, "/", "[::1]"), 0).host());

    for (String host :
        List.of(
            "",
            "example.test,other.test",
            "example.test ",
            "example\ttest",
            "[]",
            "[127.0.0.1]",
            "[::::]",
            "[fe80::1%lo]",
            "[::1]x",
            "[::1]:",
            "[::1]:abc",
            "[::1]:999999999999999999999",
            ":80",
            ".example.test",
            "example.test.",
            "example.test:",
            "example_test")) {
      assertStatus(400, request(HttpMethod.GET, "/", host), 0);
    }
  }

  @Test
  void contentLengthAndTransferEncodingParsingFailClosedAtEveryBoundary() {
    HttpRequest duplicateLength = request(HttpMethod.POST, "/", "example.test");
    duplicateLength.headers().add(HttpHeaderNames.CONTENT_LENGTH, "1");
    duplicateLength.headers().add(HttpHeaderNames.CONTENT_LENGTH, "1");
    assertStatus(400, duplicateLength, 10);

    for (String value : List.of("", "1,1", "+1", "a", "9223372036854775808")) {
      HttpRequest malformed = request(HttpMethod.POST, "/", "example.test");
      malformed.headers().set(HttpHeaderNames.CONTENT_LENGTH, value);
      assertStatus(400, malformed, Long.MAX_VALUE);
    }

    HttpRequest exactLimit = request(HttpMethod.POST, "/", "example.test");
    exactLimit.headers().set(HttpHeaderNames.CONTENT_LENGTH, "10");
    assertEquals(10, HttpRequestSecurity.validate(exactLimit, 10).declaredContentLength());

    for (String value : List.of(",", "chunked,", "gzip", "chunked, chunked")) {
      HttpRequest malformed = request(HttpMethod.POST, "/", "example.test");
      malformed.headers().set(HttpHeaderNames.TRANSFER_ENCODING, value);
      assertStatus(400, malformed, 10);
    }

    HttpRequest uppercase = request(HttpMethod.POST, "/", "example.test");
    uppercase.headers().set(HttpHeaderNames.TRANSFER_ENCODING, " CHUNKED ");
    assertTrue(HttpRequestSecurity.validate(uppercase, 10).chunked());
  }

  @Test
  void descriptorWithoutQueryOmitsQueryAttribute() throws Exception {
    HttpRequest request = request(HttpMethod.GET, "/plain?", "Example.TEST");
    var validated = HttpRequestSecurity.validate(request, 0);
    StreamingSpool.Result body = result(0, new byte[0]);
    try {
      var descriptor = HttpRequestSecurity.descriptor(validated, body, TrustLevel.TRUSTED);
      assertFalse(descriptor.attributes().containsKey("query"));
      assertEquals(List.of("example.test"), descriptor.attributes().get("host"));
      assertEquals(List.of("0"), descriptor.attributes().get("body-length"));
    } finally {
      body.close();
    }
  }

  @Test
  void connectionTokensAndForwardedHeadersAreNormalizedBeforeForwarding() {
    HttpRequest request = request(HttpMethod.GET, "/", "example.test");
    request.headers().set(HttpHeaderNames.CONNECTION, " , X-Custom, ");
    request.headers().set("X-Custom", "secret");
    request.headers().set("X-Visible", "one");
    request.headers().add("X-Visible", "two");
    request.headers().set("X-Forwarded-Host", "attacker.test");
    request.headers().set("X-Forwarded-Proto", "https");
    request.headers().set("X-Real-IP", "127.0.0.2");
    request.headers().set("Expect", "100-continue");

    Map<String, List<String>> untrusted =
        HttpRequestSecurity.originRequestHeaders(request.headers(), 7, false);
    assertFalse(untrusted.containsKey("x-custom"));
    assertFalse(untrusted.containsKey("x-forwarded-host"));
    assertFalse(untrusted.containsKey("x-forwarded-proto"));
    assertFalse(untrusted.containsKey("x-real-ip"));
    assertFalse(untrusted.containsKey("expect"));
    assertEquals(List.of("one", "two"), untrusted.get("x-visible"));
    assertEquals(List.of("7"), untrusted.get("content-length"));

    Map<String, List<String>> trusted =
        HttpRequestSecurity.originRequestHeaders(request.headers(), 7, true);
    assertEquals(List.of("attacker.test"), trusted.get("x-forwarded-host"));
    assertEquals(List.of("https"), trusted.get("x-forwarded-proto"));
    assertEquals(List.of("127.0.0.2"), trusted.get("x-real-ip"));
  }

  @Test
  void artifactMetadataValidationCoversTokenGrammarDuplicatesAndUnsafeValues() {
    String allAllowedTokenCharacters = "a0!#$%&'*+-.^_`|~";
    assertEquals(
        Map.of(allAllowedTokenCharacters, "safe"),
        HttpRequestSecurity.safeArtifactMetadata(Map.of(allAllowedTokenCharacters, "safe")));

    LinkedHashMap<String, String> duplicateNames = new LinkedHashMap<>();
    duplicateNames.put("X-Test", "one");
    duplicateNames.put("x-test", "two");
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpRequestSecurity.safeArtifactMetadata(duplicateNames));

    for (String name : List.of("", "x test", "x:y", "x@y", "x/y")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> HttpRequestSecurity.safeArtifactMetadata(Map.of(name, "value")));
    }

    for (String value : List.of("a\rb", "a\nb", "a\u0000b")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> HttpRequestSecurity.safeArtifactMetadata(Map.of("x-test", value)));
    }

    Map<String, String> nullName = new HashMap<>();
    nullName.put(null, "value");
    assertThrows(
        NullPointerException.class, () -> HttpRequestSecurity.safeArtifactMetadata(nullName));

    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("x-test", null);
    assertThrows(
        NullPointerException.class, () -> HttpRequestSecurity.safeArtifactMetadata(nullValue));
  }

  @Test
  void responseMetadataRemovesEveryConnectionNominatedAndInternalHeader() {
    var headers = DefaultHttpHeadersFactory.headersFactory().withValidation(false).newHeaders();
    headers.set(HttpHeaderNames.CONNECTION, "X-Hop, , x-second");
    headers.set("X-Hop", "one");
    headers.set("X-Second", "two");
    headers.set("X-Bounded-Origin-Internal", "hidden");
    headers.add("X-App", "a");
    headers.add("X-App", "b");

    Map<String, String> metadata = HttpRequestSecurity.artifactMetadata(headers);

    assertEquals(Map.of("x-app", "a, b"), metadata);
  }

  private static HttpRequest request(HttpMethod method, String target, String host) {
    HttpRequest request =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            method,
            target,
            DefaultHttpHeadersFactory.headersFactory().withValidation(false).newHeaders(),
            false);
    request.headers().set(HttpHeaderNames.HOST, host);
    return request;
  }

  private static void assertStatus(int expected, HttpRequest request, long maxBody) {
    HttpRequestSecurity.HttpContractException exception =
        assertThrows(
            HttpRequestSecurity.HttpContractException.class,
            () -> HttpRequestSecurity.validate(request, maxBody));
    assertEquals(expected, exception.status());
  }

  private static StreamingSpool.Result result(long length, byte[] bytes) throws Exception {
    Path path = Files.createTempFile("security-boundary-", ".tmp");
    Files.write(path, bytes);
    SpoolQuota quota = new SpoolQuota(1024, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, length, "digest", reservation);
  }
}
