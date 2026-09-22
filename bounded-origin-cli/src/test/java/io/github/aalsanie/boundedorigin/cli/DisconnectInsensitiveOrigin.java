package io.github.aalsanie.boundedorigin.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class DisconnectInsensitiveOrigin implements AutoCloseable {
  private final ServerSocket listener;
  private final boolean reset;
  private final AtomicBoolean released = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger maximum = new AtomicInteger();
  private final AtomicInteger executions = new AtomicInteger();
  private final AtomicLong iterations = new AtomicLong();
  private final CountDownLatch entered = new CountDownLatch(1);
  private final List<Thread> workers = new CopyOnWriteArrayList<>();
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final Thread acceptor;

  DisconnectInsensitiveOrigin(boolean reset) throws IOException {
    this.reset = reset;
    listener = CliTestSupport.bindLoopback(0);
    acceptor = Thread.ofPlatform().daemon(true).start(this::accept);
  }

  int port() {
    return listener.getLocalPort();
  }

  int active() {
    return active.get();
  }

  int maximum() {
    return maximum.get();
  }

  int executions() {
    return executions.get();
  }

  long iterations() {
    return iterations.get();
  }

  boolean awaitEntered() throws InterruptedException {
    return entered.await(10, TimeUnit.SECONDS);
  }

  void release() {
    released.set(true);
  }

  private void accept() {
    while (!closed.get()) {
      try {
        Socket socket = listener.accept();
        sockets.add(socket);
        Thread worker = Thread.ofPlatform().daemon(true).unstarted(() -> serve(socket));
        workers.add(worker);
        worker.start();
      } catch (IOException exception) {
        if (!closed.get()) {
          throw new AssertionError(exception);
        }
      }
    }
  }

  private void serve(Socket socket) {
    try (socket) {
      socket.setSoTimeout(5_000);
      BufferedReader input =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
      String line = input.readLine();
      if (line == null) {
        return;
      }
      do {
        line = input.readLine();
        if (line == null) {
          throw new IOException("incomplete request");
        }
      } while (!line.isEmpty());
      if (reset) {
        socket.setSoLinger(true, 0);
        socket.close();
      }
      compute();
      if (!reset) {
        socket
            .getOutputStream()
            .write(
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    .getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
      }
    } catch (IOException exception) {
      // A disconnected caller cannot interrupt compute(); transport is used only before and after
      // it.
    }
  }

  private void compute() {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
    byte[] input = new byte[32 * 1024];
    executions.incrementAndGet();
    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
    entered.countDown();
    try {
      do {
        byte[] output = digest.digest(input);
        System.arraycopy(output, 0, input, 0, output.length);
        iterations.incrementAndGet();
      } while (!released.get());
    } finally {
      active.decrementAndGet();
    }
  }

  @Override
  public void close() throws IOException {
    closed.set(true);
    release();
    listener.close();
    for (Socket socket : sockets) {
      socket.close();
    }
    try {
      if (!acceptor.join(Duration.ofSeconds(5))) {
        throw new IOException("origin acceptor failed to terminate");
      }
      for (Thread worker : workers) {
        if (!worker.join(Duration.ofSeconds(5))) {
          throw new IOException("origin computation failed to terminate");
        }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while stopping independent origin", exception);
    }
  }
}
