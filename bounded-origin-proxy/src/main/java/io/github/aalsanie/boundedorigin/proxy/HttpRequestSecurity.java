package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class HttpRequestSecurity {
  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "proxy-connection",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade");
  private static final Set<String> FORWARDED =
      Set.of("forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip");
  private static final Set<String> UNSAFE_RESPONSE =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "proxy-connection",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "content-length",
          "date",
          "set-cookie",
          "set-cookie2",
          "www-authenticate");

  private HttpRequestSecurity() {}

  static ValidatedRequest validate(HttpRequest request, long maxRequestBodyBytes) {
    Objects.requireNonNull(request, "request");
    if (maxRequestBodyBytes < 0) {
      throw new IllegalArgumentException("maxRequestBodyBytes must be non-negative");
    }
    if (request.decoderResult().isFailure()) {
      throw badRequest("HTTP decoder rejected the request");
    }
    if (HttpMethod.CONNECT.equals(request.method())) {
      throw new HttpContractException(405, "CONNECT is not supported");
    }

    String target = request.uri();
    if (target == null || target.isEmpty()) {
      throw badRequest("request target is empty");
    }
    validateVisibleAscii(target, "request target");
    if (target.indexOf('#') >= 0 || target.indexOf('\\') >= 0) {
      throw badRequest("request target contains an ambiguous path character");
    }
    validatePercentEncoding(target);

    String path;
    String query;
    if ("*".equals(target)) {
      if (!HttpMethod.OPTIONS.equals(request.method())) {
        throw badRequest("asterisk-form is only valid for OPTIONS");
      }
      path = "*";
      query = "";
    } else {
      if (!target.startsWith("/")) {
        throw badRequest("only origin-form request targets are accepted");
      }
      int queryIndex = target.indexOf('?');
      path = queryIndex < 0 ? target : target.substring(0, queryIndex);
      query = queryIndex < 0 ? "" : target.substring(queryIndex + 1);
      validatePath(path);
    }

    HttpHeaders headers = request.headers();
    List<String> hosts = headers.getAll(HttpHeaderNames.HOST);
    if (hosts.size() != 1) {
      throw badRequest("exactly one Host header is required");
    }
    String host = validateHost(hosts.getFirst());

    List<String> contentLengths = headers.getAll(HttpHeaderNames.CONTENT_LENGTH);
    List<String> transferEncodings = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
    if (!contentLengths.isEmpty() && !transferEncodings.isEmpty()) {
      throw badRequest("Content-Length and Transfer-Encoding cannot be combined");
    }

    long declaredLength = -1;
    if (!contentLengths.isEmpty()) {
      if (contentLengths.size() != 1) {
        throw badRequest("multiple Content-Length headers are forbidden");
      }
      String value = contentLengths.getFirst().trim();
      if (value.isEmpty() || value.indexOf(',') >= 0 || !isDecimal(value)) {
        throw badRequest("Content-Length is malformed");
      }
      try {
        declaredLength = Long.parseLong(value);
      } catch (NumberFormatException exception) {
        throw badRequest("Content-Length is malformed");
      }
      if (declaredLength > maxRequestBodyBytes) {
        throw new HttpContractException(413, "request body exceeds configured limit");
      }
    }

    boolean chunked = false;
    if (!transferEncodings.isEmpty()) {
      List<String> codings = new ArrayList<>();
      for (String value : transferEncodings) {
        for (String token : value.split(",", -1)) {
          String coding = token.trim().toLowerCase(Locale.ROOT);
          if (coding.isEmpty()) {
            throw badRequest("Transfer-Encoding is malformed");
          }
          codings.add(coding);
        }
      }
      if (codings.size() != 1 || !"chunked".equals(codings.getFirst())) {
        throw badRequest("only a single chunked Transfer-Encoding is accepted");
      }
      chunked = true;
    }

    return new ValidatedRequest(
        request.method().name(), target, path, query, host, declaredLength, chunked);
  }

  static RequestDescriptor descriptor(
      ValidatedRequest request, StreamingSpool.Result body, TrustLevel trustLevel) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(trustLevel, "trustLevel");
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of(request.method()));
    attributes.put("host", List.of(request.host().toLowerCase(Locale.ROOT)));
    attributes.put("path", List.of(request.path()));
    if (!request.query().isEmpty()) {
      attributes.put("query", List.of(request.query()));
    }
    attributes.put("body-length", List.of(Long.toString(body.length())));
    attributes.put("body-sha256", List.of(body.sha256()));
    return new RequestDescriptor("http.request", attributes, trustLevel);
  }

  static Map<String, List<String>> originRequestHeaders(
      HttpHeaders source, long bodyLength, boolean trustForwardedHeaders) {
    Objects.requireNonNull(source, "source");
    Set<String> connectionTokens = connectionTokens(source);
    Map<String, List<String>> sanitized = new LinkedHashMap<>();
    for (Map.Entry<String, String> header : source) {
      String lower = header.getKey().toLowerCase(Locale.ROOT);
      if (HOP_BY_HOP.contains(lower)
          || connectionTokens.contains(lower)
          || "content-length".equals(lower)
          || "expect".equals(lower)
          || lower.startsWith("x-bounded-origin-")) {
        continue;
      }
      if (!trustForwardedHeaders && FORWARDED.contains(lower)) {
        continue;
      }
      sanitized.computeIfAbsent(lower, ignored -> new ArrayList<>()).add(header.getValue());
    }
    sanitized.put("content-length", List.of(Long.toString(bodyLength)));
    Map<String, List<String>> immutable = new LinkedHashMap<>();
    sanitized.forEach((name, values) -> immutable.put(name, List.copyOf(values)));
    return Map.copyOf(immutable);
  }

  static Map<String, String> artifactMetadata(HttpHeaders source) {
    Objects.requireNonNull(source, "source");
    Set<String> connectionTokens = connectionTokens(source);
    Map<String, String> sanitized = new LinkedHashMap<>();
    for (Map.Entry<String, String> header : source) {
      String lower = header.getKey().toLowerCase(Locale.ROOT);
      if (UNSAFE_RESPONSE.contains(lower)
          || connectionTokens.contains(lower)
          || lower.startsWith("x-bounded-origin-")) {
        continue;
      }
      sanitized.merge(lower, header.getValue(), (left, right) -> left + ", " + right);
    }
    return Map.copyOf(sanitized);
  }

  static Map<String, String> safeArtifactMetadata(Map<String, String> source) {
    Objects.requireNonNull(source, "source");
    Map<String, String> sanitized = new LinkedHashMap<>();
    source.forEach(
        (name, value) -> {
          Objects.requireNonNull(name, "artifact metadata name");
          Objects.requireNonNull(value, "artifact metadata value");
          String lower = name.toLowerCase(Locale.ROOT);
          if (UNSAFE_RESPONSE.contains(lower) || lower.startsWith("x-bounded-origin-")) {
            return;
          }
          if (!isHeaderName(lower) || containsUnsafeHeaderValue(value)) {
            throw new IllegalArgumentException("artifact contains unsafe HTTP metadata");
          }
          if (sanitized.putIfAbsent(lower, value) != null) {
            throw new IllegalArgumentException("artifact contains duplicate HTTP metadata names");
          }
        });
    return Map.copyOf(sanitized);
  }

  private static Set<String> connectionTokens(HttpHeaders headers) {
    Set<String> tokens = new LinkedHashSet<>();
    for (String value : headers.getAll(HttpHeaderNames.CONNECTION)) {
      for (String token : value.split(",", -1)) {
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
          tokens.add(normalized);
        }
      }
    }
    return tokens;
  }

  private static String validateHost(String raw) {
    String host = raw.trim();
    if (host.isEmpty() || !host.equals(raw) || host.indexOf(',') >= 0 || host.indexOf('@') >= 0) {
      throw badRequest("Host header is malformed");
    }
    validateVisibleAscii(host, "Host header");

    String name;
    String port = null;
    if (host.startsWith("[")) {
      int closing = host.indexOf(']');
      if (closing <= 1) {
        throw badRequest("Host header contains an invalid IPv6 literal");
      }
      name = host.substring(1, closing);
      if (!isIpv6Literal(name)) {
        throw badRequest("Host header contains an invalid IPv6 literal");
      }
      if (closing + 1 < host.length()) {
        if (host.charAt(closing + 1) != ':' || closing + 2 == host.length()) {
          throw badRequest("Host header port is malformed");
        }
        port = host.substring(closing + 2);
      }
    } else {
      int firstColon = host.indexOf(':');
      int lastColon = host.lastIndexOf(':');
      if (firstColon != lastColon) {
        throw badRequest("IPv6 Host literals must use brackets");
      }
      if (lastColon >= 0) {
        name = host.substring(0, lastColon);
        port = host.substring(lastColon + 1);
      } else {
        name = host;
      }
      if (!isRegName(name)) {
        throw badRequest("Host header contains an invalid host name");
      }
    }

    if (port != null) {
      if (!isDecimal(port)) {
        throw badRequest("Host header port is malformed");
      }
      int parsed;
      try {
        parsed = Integer.parseInt(port);
      } catch (NumberFormatException exception) {
        throw badRequest("Host header port is malformed");
      }
      if (parsed <= 0 || parsed > 65_535) {
        throw badRequest("Host header port is outside the valid range");
      }
    }
    return host;
  }

  private static void validatePath(String path) {
    if (path.isEmpty() || path.charAt(0) != '/') {
      throw badRequest("path must use origin-form");
    }
    for (String segment : path.split("/", -1)) {
      String dots = decodeEncodedDots(segment);
      if (".".equals(dots) || "..".equals(dots)) {
        throw badRequest("dot path segments are forbidden");
      }
      String lower = segment.toLowerCase(Locale.ROOT);
      if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
        throw badRequest("encoded path separators and NUL are forbidden");
      }
    }
  }

  private static String decodeEncodedDots(String segment) {
    StringBuilder decoded = new StringBuilder(segment.length());
    for (int index = 0; index < segment.length(); index++) {
      if (index + 2 < segment.length()
          && segment.charAt(index) == '%'
          && segment.charAt(index + 1) == '2'
          && (segment.charAt(index + 2) == 'e' || segment.charAt(index + 2) == 'E')) {
        decoded.append('.');
        index += 2;
      } else {
        decoded.append(segment.charAt(index));
      }
    }
    return decoded.toString();
  }

  private static void validatePercentEncoding(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == '%') {
        if (index + 2 >= value.length()
            || Character.digit(value.charAt(index + 1), 16) < 0
            || Character.digit(value.charAt(index + 2), 16) < 0) {
          throw badRequest("request target contains malformed percent encoding");
        }
        index += 2;
      }
    }
  }

  private static void validateVisibleAscii(String value, String label) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21 || character > 0x7e) {
        throw badRequest(label + " contains invalid characters");
      }
    }
  }

  private static boolean isRegName(String value) {
    if (value.isEmpty() || value.startsWith(".") || value.endsWith(".")) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean valid =
          character >= 'a' && character <= 'z'
              || character >= 'A' && character <= 'Z'
              || character >= '0' && character <= '9'
              || character == '-'
              || character == '.';
      if (!valid) {
        return false;
      }
    }
    return true;
  }

  private static boolean isIpv6Literal(String value) {
    if (value.indexOf(':') < 0 || value.indexOf('%') >= 0) {
      return false;
    }
    try {
      return InetAddress.getByName(value) instanceof Inet6Address;
    } catch (UnknownHostException exception) {
      return false;
    }
  }

  private static boolean isHeaderName(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean valid =
          character >= 'a' && character <= 'z'
              || character >= '0' && character <= '9'
              || character == '!'
              || character == '#'
              || character == '$'
              || character == '%'
              || character == '&'
              || character == '\''
              || character == '*'
              || character == '+'
              || character == '-'
              || character == '.'
              || character == '^'
              || character == '_'
              || character == '`'
              || character == '|'
              || character == '~';
      if (!valid) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsUnsafeHeaderValue(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\r' || character == '\n' || character == 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDecimal(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') {
        return false;
      }
    }
    return true;
  }

  private static HttpContractException badRequest(String message) {
    return new HttpContractException(400, message);
  }

  record ValidatedRequest(
      String method,
      String target,
      String path,
      String query,
      String host,
      long declaredContentLength,
      boolean chunked) {}

  static final class HttpContractException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    HttpContractException(int status, String message) {
      super(message);
      this.status = status;
    }

    int status() {
      return status;
    }
  }
}
