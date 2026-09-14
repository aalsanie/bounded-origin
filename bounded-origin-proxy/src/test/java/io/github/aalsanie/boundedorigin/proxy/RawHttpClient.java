package io.github.aalsanie.boundedorigin.proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RawHttpClient implements AutoCloseable {
  private final Socket socket;
  private final InputStream input;
  private final OutputStream output;

  RawHttpClient(InetSocketAddress address) throws IOException {
    socket = new Socket();
    socket.connect(address, 2_000);
    socket.setSoTimeout(5_000);
    socket.setTcpNoDelay(true);
    input = socket.getInputStream();
    output = socket.getOutputStream();
  }

  Response request(String method, String target, Map<String, String> headers, byte[] body)
      throws IOException {
    StringBuilder request = new StringBuilder();
    request.append(method).append(' ').append(target).append(" HTTP/1.1\r\n");
    headers.forEach(
        (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
    if (!containsHeader(headers, "content-length")
        && !containsHeader(headers, "transfer-encoding")) {
      request.append("Content-Length: ").append(body.length).append("\r\n");
    }
    request.append("\r\n");
    write(request.toString());
    output.write(body);
    output.flush();
    return readResponse(method);
  }

  void write(String raw) throws IOException {
    output.write(raw.getBytes(StandardCharsets.ISO_8859_1));
    output.flush();
  }

  Response readResponse(String requestMethod) throws IOException {
    String statusLine = readLine(input);
    if (statusLine == null) {
      throw new EOFException("connection closed before response");
    }
    String[] statusParts = statusLine.split(" ", 3);
    if (statusParts.length < 2) {
      throw new IOException("malformed response status line: " + statusLine);
    }
    int status = Integer.parseInt(statusParts[1]);
    Map<String, List<String>> headers = new LinkedHashMap<>();
    while (true) {
      String line = readLine(input);
      if (line == null) {
        throw new EOFException("connection closed in response headers");
      }
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new IOException("malformed response header: " + line);
      }
      String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
    }

    byte[] body;
    if ("HEAD".equals(requestMethod)
        || status >= 100 && status < 200
        || status == 204
        || status == 205
        || status == 304) {
      body = new byte[0];
    } else if (headerContains(headers, "transfer-encoding", "chunked")) {
      body = readChunked(input);
    } else {
      String contentLength = first(headers, "content-length");
      if (contentLength != null) {
        int length = Integer.parseInt(contentLength);
        body = input.readNBytes(length);
        if (body.length != length) {
          throw new EOFException("response body ended early");
        }
      } else {
        body = input.readAllBytes();
      }
    }
    return new Response(status, immutable(headers), body);
  }

  boolean awaitClosed(Duration timeout) throws IOException {
    int previous = socket.getSoTimeout();
    socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
    try {
      return input.read() < 0;
    } catch (java.net.SocketTimeoutException exception) {
      return false;
    } finally {
      socket.setSoTimeout(previous);
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private static byte[] readChunked(InputStream input) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    while (true) {
      String line = readLine(input);
      if (line == null) {
        throw new EOFException("chunked response ended before chunk size");
      }
      int semicolon = line.indexOf(';');
      String token = (semicolon < 0 ? line : line.substring(0, semicolon)).trim();
      int size = Integer.parseUnsignedInt(token, 16);
      if (size == 0) {
        while (true) {
          String trailer = readLine(input);
          if (trailer == null) {
            throw new EOFException("chunked response ended in trailers");
          }
          if (trailer.isEmpty()) {
            return body.toByteArray();
          }
        }
      }
      byte[] chunk = input.readNBytes(size);
      if (chunk.length != size) {
        throw new EOFException("chunked response body ended early");
      }
      body.write(chunk);
      String end = readLine(input);
      if (end == null || !end.isEmpty()) {
        throw new IOException("chunk did not end with CRLF");
      }
    }
  }

  private static String readLine(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    boolean carriageReturn = false;
    while (bytes.size() <= 65_536) {
      int value = input.read();
      if (value < 0) {
        if (bytes.size() == 0 && !carriageReturn) {
          return null;
        }
        throw new EOFException("line ended before CRLF");
      }
      if (carriageReturn) {
        if (value != '\n') {
          throw new IOException("line did not end with CRLF");
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
      }
      if (value == '\r') {
        carriageReturn = true;
      } else {
        bytes.write(value);
      }
    }
    throw new IOException("line exceeds test limit");
  }

  private static boolean containsHeader(Map<String, String> headers, String expected) {
    return headers.keySet().stream().anyMatch(name -> expected.equalsIgnoreCase(name));
  }

  private static boolean headerContains(
      Map<String, List<String>> headers, String name, String expectedToken) {
    List<String> values = headers.get(name);
    if (values == null) {
      return false;
    }
    for (String value : values) {
      for (String token : value.split(",")) {
        if (expectedToken.equalsIgnoreCase(token.trim())) {
          return true;
        }
      }
    }
    return false;
  }

  private static String first(Map<String, List<String>> headers, String name) {
    List<String> values = headers.get(name);
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  private static Map<String, List<String>> immutable(Map<String, List<String>> headers) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    return Map.copyOf(copy);
  }

  record Response(int status, Map<String, List<String>> headers, byte[] body) {
    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }

    String header(String name) {
      List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
      return values == null || values.isEmpty() ? null : values.getFirst();
    }
  }
}
