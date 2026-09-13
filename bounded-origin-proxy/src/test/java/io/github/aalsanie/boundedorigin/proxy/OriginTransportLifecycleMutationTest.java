package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
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

class OriginTransportLifecycleMutationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void originResponseTimeoutInvalidatesLeaseAndReleasesCapacityBeforeClientClose()
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/timeout",
          (request, socket) -> {
            entered.countDown();
            TestOriginServer.await(release, Duration.ofMillis(500));
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.response-timeout", "PT0.05S", "request.timeout", "PT3S"));
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota)) {
        StreamingSpool.Result body = body(new byte[0], 0);
        try {
          MaterializationException failure =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("GET", "/timeout", body, 1024)));
          assertTrue(entered.await(1, TimeUnit.SECONDS));
          assertTrue(failure.getMessage().contains("origin response timed out"));
          awaitConnections(client, 0, Duration.ofSeconds(1));
          assertEquals(0, client.pendingAcquires());
          assertEquals(0, quota.files());
          assertEquals(0, quota.bytes());
          assertTrue(
              metrics
                  .prometheus(new OriginExecutorStats(0, 0, 0, 0), 0, 0, 0, 0, 0, 0)
                  .contains("bounded_origin_origin_duration_seconds_count 1\n"));
        } finally {
          release.countDown();
          body.close();
        }
      } finally {
        release.countDown();
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void interruptedExchangeInvalidatesLeaseBeforeOwningClientIsClosed() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/interrupt",
          (request, socket) -> {
            entered.countDown();
            TestOriginServer.await(release, Duration.ofSeconds(2));
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.response-timeout", "PT2S", "request.timeout", "PT3S"));
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
                        client.execute(request("GET", "/interrupt", body, 1024));
                      } catch (MaterializationException exception) {
                        failure.set(exception);
                        interrupted.set(Thread.currentThread().isInterrupted());
                      }
                    });
        try {
          assertTrue(entered.await(1, TimeUnit.SECONDS));
          worker.interrupt();
          assertTrue(worker.join(Duration.ofSeconds(1)));
          assertTrue(failure.get() != null);
          assertTrue(failure.get().getMessage().contains("origin request was interrupted"));
          assertTrue(interrupted.get());
          awaitConnections(client, 0, Duration.ofSeconds(1));
          assertEquals(0, client.pendingAcquires());
          assertEquals(0, quota.files());
          assertEquals(0, quota.bytes());
        } finally {
          release.countDown();
          body.close();
        }
      } finally {
        release.countDown();
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void oversizedChunkedOriginBodyFailsAndReleasesResponseSpool() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/oversized",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: keep-alive\r\n\r\n"
                    + "3\r\nabc\r\n0\r\n\r\n");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[0], 0);
        try {
          MaterializationException failure =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("GET", "/oversized", body, 2)));
          assertTrue(failure.getMessage().contains("configured limit"));
          awaitConnections(client, 0, Duration.ofSeconds(1));
          awaitQuota(quota, 0, 0, Duration.ofSeconds(1));
        } finally {
          body.close();
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void originCloseBeforeFinalResponseReleasesLeaseAndLeavesPoolReusable() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond("/closed", (request, socket) -> false);
      origin.fixed("/ok", 200, "ok");
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result firstBody = body(new byte[0], 0);
        StreamingSpool.Result secondBody = body(new byte[0], 0);
        Artifact artifact = null;
        try {
          MaterializationException failure =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("GET", "/closed", firstBody, 1024)));
          assertTrue(failure.getMessage().contains("origin response failed"));
          awaitConnections(client, 0, Duration.ofSeconds(1));

          artifact = client.execute(request("GET", "/ok", secondBody, 1024));
          assertEquals(200, artifact.statusCode());
          assertEquals(2, artifact.contentLength());
          assertEquals(1, client.openConnections());
        } finally {
          firstBody.close();
          secondBody.close();
          deleteTemporary(artifact);
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void reusableResponseKeepsExactlyOneOriginConnectionAcrossRequests() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/keep-alive",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result firstBody = body(new byte[0], 0);
        StreamingSpool.Result secondBody = body(new byte[0], 0);
        Artifact first = null;
        Artifact second = null;
        try {
          first = client.execute(request("GET", "/keep-alive", firstBody, 1024));
          second = client.execute(request("GET", "/keep-alive", secondBody, 1024));
          assertEquals(200, first.statusCode());
          assertEquals(200, second.statusCode());
          assertEquals(1, origin.connections());
          assertEquals(1, client.openConnections());
        } finally {
          firstBody.close();
          secondBody.close();
          deleteTemporary(first);
          deleteTemporary(second);
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private OriginRequest request(
      String method, String target, StreamingSpool.Result body, long maxResponseBytes) {
    return new OriginRequest(
        method, target, Map.of("host", List.of("example.test")), body, maxResponseBytes);
  }

  private StreamingSpool.Result body(byte[] bytes, long declaredLength) throws Exception {
    Path path = Files.createTempFile(temporaryDirectory, "origin-lifecycle-request-", ".tmp");
    Files.write(path, bytes);
    SpoolQuota quota = new SpoolQuota(4096, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, declaredLength, "digest", reservation);
  }

  private static void awaitConnections(NettyOriginClient client, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (client.openConnections() != expected && System.nanoTime() - deadline < 0) {
      Thread.sleep(5);
    }
    assertEquals(expected, client.openConnections());
  }

  private static void awaitQuota(SpoolQuota quota, long bytes, int files, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while ((quota.bytes() != bytes || quota.files() != files) && System.nanoTime() - deadline < 0) {
      Thread.sleep(5);
    }
    assertEquals(bytes, quota.bytes());
    assertEquals(files, quota.files());
  }

  private static void deleteTemporary(Artifact artifact) {
    if (artifact != null && artifact.body() instanceof TemporaryArtifactBody body) {
      body.delete();
    }
  }
}
