package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpRequestSecurityMutationContractTest {
  @Test
  void requestTargetBoundariesPreserveTheIntendedFailureReason() {
    assertContract(400, "request target contains an ambiguous path character", request("#", "example.test"), 0);
    assertContract(400, "request target contains an ambiguous path character", request("\\", "example.test"), 0);
    assertContract(400, "request target contains invalid characters", request("/\u0020", "example.test"), 0);
    assertContract(400, "request target contains invalid characters", request("/\u007f", "example.test"), 0);
    assertContract(400, "request target contains malformed percent encoding", request("/%0", "example.test"), 0);
    assertContract(400, "encoded path separators and NUL are forbidden", request("/%00", "example.test"), 0);
    assertContract(400, "dot path segments are forbidden", request("/%2e", "example.test"), 0);

    assertEquals("/!", HttpRequestSecurity.validate(request("/!", "example.test"), 0).path());
    assertEquals("/~", HttpRequestSecurity.validate(request("/~", "example.test"), 0).path());
    assertEquals(
        "/a%20b", HttpRequestSecurity.validate(request("/a%20b", "example.test"), 0).path());
  }

  @Test
  void hostAndPortBoundariesAreExact() {
    for (String host : List.of("a", "z", "A", "Z", "0", "9", "a-b", "a.b")) {
      assertEquals(host, HttpRequestSecurity.validate(request("/", host), 0).host());
    }

    assertEquals(
        "[::1]:1", HttpRequestSecurity.validate(request("/", "[::1]:1"), 0).host());
    assertEquals(
        "[::1]:65535", HttpRequestSecurity.validate(request("/", "[::1]:65535"), 0).host());

    assertContract(400, "Host header is malformed", request("/", "@example.test"), 0);
    assertContract(400, "Host header is malformed", request("/", ",example.test"), 0);
    assertContract(400, "IPv6 Host literals must use brackets", request("/", "a:b:c"), 0);
    assertContract(400, "Host header port is outside the valid range", request("/", "example.test:0"), 0);
    assertContract(
        400,
        "Host header port is outside the valid range",
        request("/", "example.test:65536"),
        0);
    assertContract(400, "Host header port is outside the valid range", request("/", "[::1]:0"), 0);
    assertContract(
        400, "Host header port is outside the valid range", request("/", "[::1]:65536"), 0);
  }

  @Test
  void contentLengthLimitDistinguishesExactLimitFromOneByteOver() {
    HttpRequest exact = request("/", "example.test");
    exact.headers().set(HttpHeaderNames.CONTENT_LENGTH, "10");
    assertEquals(10, HttpRequestSecurity.validate(exact, 10).declaredContentLength());

    HttpRequest over = request("/", "example.test");
    over.headers().set(HttpHeaderNames.CONTENT_LENGTH, "11");
    assertContract(413, "request body exceeds configured limit", over, 10);

    HttpRequest zero = request("/", "example.test");
    zero.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
    assertEquals(0, HttpRequestSecurity.validate(zero, 0).declaredContentLength());
  }

  @Test
  void forwardingTrustBoundaryCannotBeChangedByHeaderCasing() {
    HttpRequest source = request("/", "example.test");
    source.headers().set("FoRwArDeD", "for=192.0.2.1");
    source.headers().set("X-FoRwArDeD-FoR", "192.0.2.1");
    source.headers().set("X-Bounded-Origin-Trust", "TRUSTED");
    source.headers().set("Connection", "X-Remove");
    source.headers().set("X-Remove", "hidden");

    var untrusted = HttpRequestSecurity.originRequestHeaders(source.headers(), 0, false);
    assertFalse(untrusted.containsKey("forwarded"));
    assertFalse(untrusted.containsKey("x-forwarded-for"));
    assertFalse(untrusted.containsKey("x-bounded-origin-trust"));
    assertFalse(untrusted.containsKey("x-remove"));

    var trusted = HttpRequestSecurity.originRequestHeaders(source.headers(), 0, true);
    assertEquals(List.of("for=192.0.2.1"), trusted.get("forwarded"));
    assertEquals(List.of("192.0.2.1"), trusted.get("x-forwarded-for"));
    assertTrue(trusted.containsKey("content-length"));
    assertFalse(trusted.containsKey("x-bounded-origin-trust"));
  }

  private static HttpRequest request(String target, String host) {
    HttpRequest request =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            target,
            DefaultHttpHeadersFactory.headersFactory().withValidation(false).newHeaders(),
            false);
    request.headers().set(HttpHeaderNames.HOST, host);
    return request;
  }

  private static void assertContract(
      int expectedStatus, String expectedMessage, HttpRequest request, long maxBodyBytes) {
    try {
      HttpRequestSecurity.validate(request, maxBodyBytes);
      throw new AssertionError("expected request validation to fail");
    } catch (HttpRequestSecurity.HttpContractException exception) {
      assertEquals(expectedStatus, exception.status());
      assertEquals(expectedMessage, exception.getMessage());
    }
  }
}
