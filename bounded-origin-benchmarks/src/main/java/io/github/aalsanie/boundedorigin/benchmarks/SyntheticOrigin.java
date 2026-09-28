package io.github.aalsanie.boundedorigin.benchmarks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SyntheticOrigin implements Closeable {
  private final HttpServer server;
  private final ExecutorService workers;
  private final OriginJournal journal;
  private final CountDownLatch release;
  private final ThreadMXBean cpu;
  private final long iterations;
  private final int bytes;

  SyntheticOrigin(Path events, long iterations, int bytes, boolean held) throws IOException {
    if (iterations < 0 || iterations > 1_000_000_000L || bytes < 64 || bytes > 16_777_216) {
      throw new IllegalArgumentException("invalid synthetic work parameters");
    }
    this.iterations = iterations;
    this.bytes = bytes;
    cpu = ManagementFactory.getThreadMXBean();
    if (!cpu.isCurrentThreadCpuTimeSupported()) {
      throw new IllegalStateException("platform thread CPU accounting is required");
    }
    cpu.setThreadCpuTimeEnabled(true);
    release = new CountDownLatch(held ? 1 : 0);
    journal = new OriginJournal(events);
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
    } catch (IOException failure) {
      journal.close();
      throw failure;
    }
    workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    server.createContext(
        "/control/stats",
        exchange -> respond(exchange, 200, snapshot().getBytes(StandardCharsets.UTF_8)));
    server.createContext(
        "/control/release",
        exchange -> {
          release.countDown();
          respond(exchange, 200, new byte[] {1});
        });
    server.createContext("/", this::work);
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  String snapshot() {
    return journal.snapshot();
  }

  private void work(HttpExchange exchange) throws IOException {
    String target = exchange.getRequestURI().toASCIIString();
    long id = journal.start(target);
    boolean reset = exchange.getRequestURI().getPath().startsWith("/reset/");
    if (reset) {
      exchange.close();
    }
    try {
      release.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("synthetic work interrupted before completion", exception);
    }
    long before = cpu.getCurrentThreadCpuTime();
    byte[] body =
        SyntheticWork.compute(SyntheticWork.identity(exchange.getRequestURI()), iterations, bytes);
    long consumed = cpu.getCurrentThreadCpuTime() - before;
    // Completion is journaled only after all expensive work, before a terminal HTTP response.
    journal.finish(id, target, consumed);
    if (!reset) {
      try {
        respond(
            exchange, exchange.getRequestURI().getPath().startsWith("/fail/") ? 500 : 200, body);
      } catch (IOException exception) {
        journal.writeFailed();
        exchange.close();
      }
    }
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.getResponseHeaders().set("Cache-Control", "public");
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  }

  @Override
  public void close() throws IOException {
    release.countDown();
    server.stop(0);
    workers.close();
    journal.close();
  }

  public static void main(String[] args) throws IOException {
    run(args, System.in);
  }

  static void run(String[] args, InputStream control) throws IOException {
    if (args.length != 4 || !(args[3].equals("held") || args[3].equals("free"))) {
      throw new IllegalArgumentException("output-directory iterations body-bytes held|free");
    }
    Path output = Path.of(args[0]);
    SyntheticOrigin origin =
        new SyntheticOrigin(
            output.resolve("origin.jsonl"),
            Long.parseLong(args[1]),
            Integer.parseInt(args[2]),
            args[3].equals("held"));
    try (origin) {
      Files.writeString(
          output.resolve("origin-ready.json"),
          "{\"port\":" + origin.port() + ",\"pid\":" + ProcessHandle.current().pid() + "}",
          StandardOpenOption.CREATE_NEW);
      control.read();
    }
    Files.writeString(
        output.resolve("origin-final.json"), origin.snapshot(), StandardOpenOption.CREATE_NEW);
  }
}
