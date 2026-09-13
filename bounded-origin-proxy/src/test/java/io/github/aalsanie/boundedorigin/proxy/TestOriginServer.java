package io.github.aalsanie.boundedorigin.proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TestOriginServer implements AutoCloseable {
  private final ServerSocket server;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final Map<String, Responder> responders = new ConcurrentHashMap<>();
  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicInteger requests = new AtomicInteger();
  private final Thread acceptThread;

  TestOriginServer() throws IOException {
    server = new ServerSocket();
    server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    acceptThread = Thread.ofVirtual().name("test-origin-accept").start(this::acceptLoop);
  }

  int port() {
    return server.getLocalPort();
  }

  int connections() {
    return connections.get();
  }

  int requests() {
    return requests.get();
  }

  void respond(String path, Responder responder) {
    responders.put(
        Objects.requireNonNull(path, "path"), Objects.requireNonNull(responder, "responder"));
  }

  void fixed(String path, int status, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    respond(
        path,
        (request, socket) -> {
          write(
              socket,
              "HTTP/1.1 "
                  + status
                  + " Test\r\nContent-Type: text/plain\r\nContent-Length: "
                  + bytes.length
                  + "\r\nConnection: keep-alive\r\n\r\n");
          socket.getOutputStream().write(bytes);
          socket.getOutputStream().flush();
          return true;
        });
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      server.close();
    } catch (IOException ignored) {
    }
    for (Socket socket : sockets) {
      closeSocket(socket);
    }
    try {
      acceptThread.join(Duration.ofSeconds(2));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  static void write(Socket socket, String value) throws IOException {
    socket.getOutputStream().write(value.getBytes(StandardCharsets.ISO_8859_1));
    socket.getOutputStream().flush();
  }

  static boolean await(CountDownLatch latch, Duration timeout) throws InterruptedException {
    return latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  private void acceptLoop() {
    while (!closed.get()) {
      try {
        Socket socket = server.accept();
        sockets.add(socket);
        connections.incrementAndGet();
        Thread.ofVirtual().name("test-origin-connection-", 0).start(() -> handle(socket));
      } catch (SocketException exception) {
        if (!closed.get()) {
          throw new AssertionError(exception);
        }
      } catch (IOException exception) {
        if (!closed.get()) {
          throw new AssertionError(exception);
        }
      }
    }
  }

  private void handle(Socket socket) {
    try (socket) {
      socket.setTcpNoDelay(true);
      while (!closed.get() && !socket.isClosed()) {
        Request request = readRequest(socket.getInputStream());
        if (request == null) {
          return;
        }
        requests.incrementAndGet();
        Responder responder = responders.get(request.path());
        if (responder == null) {
          responder =
              (ignored, connection) -> {
                write(
                    connection,
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n");
                return true;
              };
        }
        if (!responder.respond(request, socket)) {
          return;
        }
      }
    } catch (Exception exception) {
      if (!closed.get() && !socket.isClosed()) {
        throw new AssertionError(exception);
      }
    } finally {
      sockets.remove(socket);
    }
  }

  private static Request readRequest(InputStream input) throws IOException {
    String requestLine = readLine(input);
    if (requestLine == null) {
      return null;
    }
    if (requestLine.isEmpty()) {
      throw new IOException("empty request line");
    }
    String[] parts = requestLine.split(" ", 3);
    if (parts.length != 3) {
      throw new IOException("malformed request line");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    while (true) {
      String line = readLine(input);
      if (line == null) {
        throw new EOFException("origin request ended in headers");
      }
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw new IOException("malformed request header");
      }
      headers.put(
          line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
          line.substring(colon + 1).trim());
    }
    long length = Long.parseLong(headers.getOrDefault("content-length", "0"));
    if (length > Integer.MAX_VALUE) {
      throw new IOException("test request is too large");
    }
    byte[] body = input.readNBytes((int) length);
    if (body.length != length) {
      throw new EOFException("origin request body ended early");
    }
    return new Request(parts[0], parts[1], Map.copyOf(headers), body);
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

  private static void closeSocket(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }

  @FunctionalInterface
  interface Responder {
    boolean respond(Request request, Socket socket) throws Exception;
  }

  record Request(String method, String target, Map<String, String> headers, byte[] body) {
    String path() {
      int query = target.indexOf('?');
      return query < 0 ? target : target.substring(0, query);
    }
  }
}
