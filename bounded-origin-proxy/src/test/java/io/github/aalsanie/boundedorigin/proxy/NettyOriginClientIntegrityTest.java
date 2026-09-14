package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NettyOriginClientIntegrityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void requestBodyShorterThanDeclaredLengthFailsBeforeOriginExecutionCompletes() throws Exception {
    try (PassiveOrigin origin = new PassiveOrigin()) {
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[] {1}, 2);
        try {
          MaterializationException failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  MaterializationException.class, () -> client.execute(request("/short", body)));
          assertTrue(failure.getMessage().contains("failed to stream request to origin"));
          assertTrue(failure.getCause() instanceof IOException);
        } finally {
          body.close();
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void requestBodyLongerThanDeclaredLengthFailsBeforeFinalContent() throws Exception {
    try (PassiveOrigin origin = new PassiveOrigin()) {
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[] {1, 2}, 1);
        try {
          MaterializationException failure =
              org.junit.jupiter.api.Assertions.assertThrows(
                  MaterializationException.class, () -> client.execute(request("/long", body)));
          assertTrue(failure.getMessage().contains("failed to stream request to origin"));
          assertTrue(failure.getCause() instanceof IOException);
        } finally {
          body.close();
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void fullSizedOriginWriteChunkCompletesNormally() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/exact",
          (request, socket) -> {
            assertEquals(2, request.body().length);
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("http.chunk-bytes", "2"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[] {1, 2}, 2);
        Artifact artifact = null;
        try {
          artifact = client.execute(request("/exact", body));
          assertEquals(200, artifact.statusCode());
          assertEquals(2, artifact.contentLength());
        } finally {
          body.close();
          deleteTemporary(artifact);
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void interruptWhileWaitingForOriginResponseCancelsExchangeAndPreservesInterruptStatus()
      throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/wait",
          (request, socket) -> {
            originEntered.countDown();
            TestOriginServer.await(releaseOrigin, Duration.ofSeconds(5));
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.response-timeout", "PT4S", "request.timeout", "PT5S"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[0], 0);
        AtomicReference<MaterializationException> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread worker =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        client.execute(request("/wait", body));
                      } catch (MaterializationException exception) {
                        failure.set(exception);
                        interrupted.set(Thread.currentThread().isInterrupted());
                      }
                    });
        assertTrue(originEntered.await(2, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(Duration.ofSeconds(2));
        releaseOrigin.countDown();
        body.close();

        assertTrue(failure.get() != null);
        assertTrue(failure.get().getMessage().contains("origin request was interrupted"));
        assertTrue(interrupted.get());
      } finally {
        releaseOrigin.countDown();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void interruptWhileWaitingForPoolAcquireCancelsPendingAcquire() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/hold",
          (request, socket) -> {
            firstEntered.countDown();
            assertTrue(TestOriginServer.await(releaseFirst, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "1"),
                  Map.entry("origin.acquire-timeout", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S")));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result firstBody = body(new byte[0], 0);
        StreamingSpool.Result secondBody = body(new byte[0], 0);
        AtomicReference<Artifact> firstArtifact = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        firstArtifact.set(client.execute(request("/hold", firstBody)));
                      } catch (Throwable throwable) {
                        firstFailure.set(throwable);
                      }
                    });
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        AtomicReference<MaterializationException> secondFailure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread second =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        client.execute(request("/second", secondBody));
                      } catch (MaterializationException exception) {
                        secondFailure.set(exception);
                        interrupted.set(Thread.currentThread().isInterrupted());
                      }
                    });
        awaitPending(client, 1, Duration.ofSeconds(2));
        second.interrupt();
        second.join(Duration.ofSeconds(2));

        assertTrue(secondFailure.get() != null);
        assertTrue(secondFailure.get().getMessage().contains("interrupted while acquiring"));
        assertTrue(interrupted.get());
        assertEquals(0, client.pendingAcquires());

        releaseFirst.countDown();
        first.join(Duration.ofSeconds(2));
        assertTrue(firstFailure.get() == null);
        assertTrue(firstArtifact.get() != null);
        deleteTemporary(firstArtifact.get());
        firstBody.close();
        secondBody.close();
      } finally {
        releaseFirst.countDown();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private OriginRequest request(String target, StreamingSpool.Result body) {
    return new OriginRequest("POST", target, Map.of("host", List.of("example.test")), body, 1024);
  }

  private StreamingSpool.Result body(byte[] bytes, long declaredLength) throws Exception {
    Path path = Files.createTempFile(temporaryDirectory, "origin-request-", ".tmp");
    Files.write(path, bytes);
    SpoolQuota quota = new SpoolQuota(4096, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, declaredLength, "digest", reservation);
  }

  private static void awaitPending(NettyOriginClient client, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (client.pendingAcquires() == expected) {
        return;
      }
      Thread.sleep(5);
    }
    assertEquals(expected, client.pendingAcquires());
  }

  private static void deleteTemporary(Artifact artifact) {
    if (artifact != null && artifact.body() instanceof TemporaryArtifactBody body) {
      body.delete();
    }
  }

  private static final class PassiveOrigin implements AutoCloseable {
    private final ServerSocket server;
    private final Thread acceptor;

    private PassiveOrigin() throws IOException {
      server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      acceptor =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      socket.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                    }
                  });
    }

    private int port() {
      return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
      server.close();
      try {
        acceptor.join(Duration.ofSeconds(2));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while closing passive origin", exception);
      }
    }
  }
}
