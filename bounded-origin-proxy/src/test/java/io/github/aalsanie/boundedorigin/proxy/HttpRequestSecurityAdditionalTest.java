package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpRequestSecurityAdditionalTest {
  @TempDir Path temporaryDirectory;

  @Test
  void asteriskFormIsRestrictedToOptionsAndConnectIsRejected() {
    var options = HttpRequestSecurity.validate(request(HttpMethod.OPTIONS, "*", "example.test"), 0);
    assertEquals("*", options.path());
    assertEquals("", options.query());

    assertStatus(400, request(HttpMethod.GET, "*", "example.test"), 0);
    assertStatus(405, request(HttpMethod.CONNECT, "example.test:443", "example.test"), 0);
  }

  @Test
  void requestTargetRejectsFragmentsControlsAndEncodedSeparators() {
    assertStatus(400, request(HttpMethod.GET, "/x#fragment", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/bad path", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/bad\u0080", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/a/%2f/b", "example.test"), 0);
    assertStatus(400, request(HttpMethod.GET, "/a/%2F/b", "example.test"), 0);
  }

  @Test
  void hostAuthorityAcceptsBoundaryPortsAndRejectsAmbiguousAuthorities() {
    assertEquals(
        "example.test:1",
        HttpRequestSecurity.validate(request(HttpMethod.GET, "/", "example.test:1"), 0).host());
    assertEquals(
        "[::1]:65535",
        HttpRequestSecurity.validate(request(HttpMethod.GET, "/", "[::1]:65535"), 0).host());

    for (String host :
        List.of(
            "user@example.test",
            "bad_name",
            "::1",
            "example.test:0",
            "example.test:65536",
            "[::1]:0",
            "[::1]:65536")) {
      assertStatus(400, request(HttpMethod.GET, "/", host), 0);
    }
  }

  @Test
  void transferEncodingAcrossMultipleHeaderFieldsIsRejected() {
    HttpRequest request = request(HttpMethod.POST, "/", "example.test");
    request.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
    request.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip");
    assertStatus(400, request, 16);
  }

  @Test
  void contentLengthAboveExactConfiguredBoundaryIsRejected() {
    HttpRequest request = request(HttpMethod.POST, "/", "example.test");
    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "17");
    assertStatus(413, request, 16);
  }

  @Test
  void safeArtifactMetadataDropsProtocolManagedAndInternalFields() {
    Map<String, String> sanitized =
        HttpRequestSecurity.safeArtifactMetadata(
            Map.ofEntries(
                Map.entry("Content-Length", "10"),
                Map.entry("Set-Cookie", "a=b"),
                Map.entry("X-Bounded-Origin-Internal", "secret"),
                Map.entry("X-App", "visible")));

    assertEquals(Map.of("x-app", "visible"), sanitized);
  }

  @Test
  void descriptorIncludesNonEmptyQueryAndUntrustedTrustLevel() throws Exception {
    HttpRequest request = request(HttpMethod.GET, "/search?q=value", "Example.TEST");
    var validated = HttpRequestSecurity.validate(request, 0);
    StreamingSpool.Result body = result();
    try {
      var descriptor = HttpRequestSecurity.descriptor(validated, body, TrustLevel.UNTRUSTED);
      assertEquals(List.of("q=value"), descriptor.attributes().get("query"));
      assertEquals(List.of("example.test"), descriptor.attributes().get("host"));
      assertEquals(TrustLevel.UNTRUSTED, descriptor.trustLevel());
    } finally {
      body.close();
    }
  }

  @Test
  void trustedForwardingPreservesForwardedHeaderButStillRemovesInternalHeaders() {
    HttpRequest request = request(HttpMethod.GET, "/", "example.test");
    request.headers().set("Forwarded", "for=192.0.2.1");
    request.headers().set("X-Bounded-Origin-Trusted", "yes");

    Map<String, List<String>> headers =
        HttpRequestSecurity.originRequestHeaders(request.headers(), 0, true);

    assertEquals(List.of("for=192.0.2.1"), headers.get("forwarded"));
    assertFalse(headers.containsKey("x-bounded-origin-trusted"));
  }

  private StreamingSpool.Result result() throws Exception {
    Path path = Files.createTempFile(temporaryDirectory, "security-query-", ".tmp");
    Files.write(path, new byte[0]);
    SpoolQuota quota = new SpoolQuota(1024, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    return new StreamingSpool.Result(path, 0, "digest", reservation);
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
}
