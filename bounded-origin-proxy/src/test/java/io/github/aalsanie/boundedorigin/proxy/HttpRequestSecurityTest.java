package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpRequestSecurityTest {
  @Test
  void validOriginFormRequestProducesStableDescriptor() {
    HttpRequest request = request(HttpMethod.POST, "/a/b?q=1", "Example.COM:8080");
    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "3");
    var validated = HttpRequestSecurity.validate(request, 10);
    StreamingSpool.Result body = result(3, "abc");

    RequestDescriptor descriptor =
        HttpRequestSecurity.descriptor(validated, body, TrustLevel.UNTRUSTED);

    assertEquals("POST", validated.method());
    assertEquals("/a/b", validated.path());
    assertEquals("q=1", validated.query());
    assertEquals(3, validated.declaredContentLength());
    assertEquals("http.request", descriptor.name());
    assertEquals(List.of("example.com:8080"), descriptor.attributes().get("host"));
    assertEquals(List.of("/a/b"), descriptor.attributes().get("path"));
    assertEquals(List.of("q=1"), descriptor.attributes().get("query"));
    assertEquals(TrustLevel.UNTRUSTED, descriptor.trustLevel());
    body.close();
  }

  @Test
  void optionsAsteriskAndChunkedRequestsAreAccepted() {
    HttpRequest options = request(HttpMethod.OPTIONS, "*", "localhost");
    assertEquals("*", HttpRequestSecurity.validate(options, 0).path());

    HttpRequest chunked = request(HttpMethod.POST, "/upload", "localhost");
    chunked.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
    var validated = HttpRequestSecurity.validate(chunked, 100);
    assertTrue(validated.chunked());
    assertEquals(-1, validated.declaredContentLength());
  }

  @Test
  void forwardingRemovesHopByHopPrivilegedAndUntrustedForwardingHeaders() {
    HttpRequest request = request(HttpMethod.GET, "/x", "example.com");
    request.headers().set("Connection", "x-remove, keep-alive");
    request.headers().set("X-Remove", "secret");
    request.headers().set("Proxy-Connection", "close");
    request.headers().set("X-Bounded-Origin-Trusted", "yes");
    request.headers().set("Forwarded", "for=attacker");
    request.headers().set("X-Forwarded-For", "attacker");
    request.headers().set("X-App", "ok");

    Map<String, List<String>> sanitized =
        HttpRequestSecurity.originRequestHeaders(request.headers(), 12, false);

    assertEquals(List.of("ok"), sanitized.get("x-app"));
    assertEquals(List.of("12"), sanitized.get("content-length"));
    assertFalse(sanitized.containsKey("connection"));
    assertFalse(sanitized.containsKey("x-remove"));
    assertFalse(sanitized.containsKey("proxy-connection"));
    assertFalse(sanitized.containsKey("x-bounded-origin-trusted"));
    assertFalse(sanitized.containsKey("forwarded"));
    assertFalse(sanitized.containsKey("x-forwarded-for"));

    Map<String, List<String>> trusted =
        HttpRequestSecurity.originRequestHeaders(request.headers(), 12, true);
    assertEquals(List.of("for=attacker"), trusted.get("forwarded"));
  }

  @Test
  void responseMetadataDropsUnsafeTransportAndCredentialState() {
    var headers = new io.netty.handler.codec.http.DefaultHttpHeaders();
    headers.set("Connection", "x-hop");
    headers.set("X-Hop", "remove");
    headers.set("Content-Length", "5");
    headers.set("Set-Cookie", "a=b");
    headers.set("Authentication-Info", "nextnonce=secret");
    headers.set("Proxy-Authentication-Info", "nextnonce=secret");
    headers.set("X-Bounded-Origin-Policy", "fake");
    headers.set("Content-Type", "text/plain");
    headers.add("Cache-Control", "private");
    headers.add("Cache-Control", "max-age=0");

    Map<String, String> metadata = HttpRequestSecurity.artifactMetadata(headers);

    assertEquals("text/plain", metadata.get("content-type"));
    assertEquals("private, max-age=0", metadata.get("cache-control"));
    assertFalse(metadata.containsKey("content-length"));
    assertFalse(metadata.containsKey("set-cookie"));
    assertFalse(metadata.containsKey("authentication-info"));
    assertFalse(metadata.containsKey("proxy-authentication-info"));
    assertFalse(metadata.containsKey("x-hop"));
    assertFalse(metadata.containsKey("x-bounded-origin-policy"));
  }

  @Test
  void persistedArtifactMetadataIsValidatedAgainBeforeServing() {
    Map<String, String> safe =
        HttpRequestSecurity.safeArtifactMetadata(
            Map.of(
                "Content-Type",
                "text/plain",
                "Connection",
                "close",
                "Set-Cookie",
                "a=b",
                HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH,
                "123"));
    assertEquals(Map.of("content-type", "text/plain"), safe);

    assertThrows(
        IllegalArgumentException.class,
        () -> HttpRequestSecurity.safeArtifactMetadata(Map.of("bad name", "x")));
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpRequestSecurity.safeArtifactMetadata(Map.of("x", "a\r\nb")));
  }

  @Test
  void malformedTargetsHostsAndLengthsFailClosed() {
    assertStatus(405, request(HttpMethod.CONNECT, "example.com:443", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "http://example.com/x", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/a/../b", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/a/%2e%2e/b", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/a/%2F/b", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/bad%zz", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/bad path", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/x#fragment", "example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "*", "example.com"), 10);

    HttpRequest missingHost = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    assertStatus(400, missingHost, 10);

    assertStatus(400, request(HttpMethod.GET, "/", " bad"), 10);
    assertStatus(400, request(HttpMethod.GET, "/", "user@example.com"), 10);
    assertStatus(400, request(HttpMethod.GET, "/", "example.com:0"), 10);
    assertStatus(400, request(HttpMethod.GET, "/", "example.com:65536"), 10);
    assertStatus(400, request(HttpMethod.GET, "/", "2001:db8::1"), 10);

    HttpRequest duplicateHost = request(HttpMethod.GET, "/", "example.com");
    duplicateHost.headers().add(HttpHeaderNames.HOST, "other.example");
    assertStatus(400, duplicateHost, 10);

    HttpRequest malformedLength = request(HttpMethod.POST, "/", "example.com");
    malformedLength.headers().set(HttpHeaderNames.CONTENT_LENGTH, "+1");
    assertStatus(400, malformedLength, 10);

    HttpRequest oversized = request(HttpMethod.POST, "/", "example.com");
    oversized.headers().set(HttpHeaderNames.CONTENT_LENGTH, "11");
    assertStatus(413, oversized, 10);

    HttpRequest ambiguous = request(HttpMethod.POST, "/", "example.com");
    ambiguous.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
    ambiguous.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
    assertStatus(400, ambiguous, 10);

    HttpRequest badTransfer = request(HttpMethod.POST, "/", "example.com");
    badTransfer.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
    assertStatus(400, badTransfer, 10);
  }

  @Test
  void ipv6HostIsAcceptedOnlyInBracketForm() {
    var validated = HttpRequestSecurity.validate(request(HttpMethod.GET, "/", "[::1]:8080"), 0);
    assertEquals("[::1]:8080", validated.host());
    assertStatus(400, request(HttpMethod.GET, "/", "[invalid]"), 0);
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

  private static StreamingSpool.Result result(long length, String text) {
    try {
      Path path = java.nio.file.Files.createTempFile("security-", ".tmp");
      java.nio.file.Files.writeString(path, text);
      SpoolQuota quota = new SpoolQuota(100, 1);
      SpoolQuota.Reservation reservation = quota.openFile();
      reservation.reserve(length);
      return new StreamingSpool.Result(path, length, "digest", reservation);
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }
}
