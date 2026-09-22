package io.github.aalsanie.boundedorigin.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

final class SyntheticOrigin implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger maxActive = new AtomicInteger();
  private final List<String> targets = new CopyOnWriteArrayList<>();
  private final AtomicReference<CountDownLatch> responseGate =
      new AtomicReference<>(new CountDownLatch(0));

  SyntheticOrigin() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  int requestCount() {
    return requests.get();
  }

  int maxActiveRequests() {
    return maxActive.get();
  }

  List<String> targets() {
    return List.copyOf(targets);
  }

  void blockResponses() {
    responseGate.set(new CountDownLatch(1));
  }

  void releaseResponses() {
    responseGate.get().countDown();
  }

  boolean awaitRequestsAtLeast(int expected, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (requests.get() >= expected) {
        return true;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
    return requests.get() >= expected;
  }

  @Override
  public void close() {
    responseGate.get().countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    int currentActive = active.incrementAndGet();
    maxActive.accumulateAndGet(currentActive, Math::max);
    String target = exchange.getRequestURI().toString();
    targets.add(target);
    try {
      exchange.getRequestBody().readAllBytes();
      if (!awaitGate(responseGate.get())) {
        respond(exchange, 504, "origin gate timed out\n");
        return;
      }
      respond(exchange, 200, "origin:" + target + "\n");
    } finally {
      active.decrementAndGet();
      exchange.close();
    }
  }

  private static boolean awaitGate(CountDownLatch gate) {
    try {
      return gate.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("content-type", "text/plain; charset=utf-8");
    exchange.getResponseHeaders().set("cache-control", "public");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
