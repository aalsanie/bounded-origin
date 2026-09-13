package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NettyOriginClientBoundaryContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void successfulExchangeRecordsElapsedDurationAndExactRequestFraming() throws Exception {
    byte[] requestBytes = new byte[] {1, 2, 3, 4, 5};
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/framing",
          (request, socket) -> {
            assertEquals("keep-alive", request.headers().get("connection"));
            assertEquals("5", request.headers().get("content-length"));
            assertArrayEquals(requestBytes, request.body());
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("http.chunk-bytes", "2"));
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota)) {
        StreamingSpool.Result body = body(requestBytes, requestBytes.length);
        Artifact artifact = null;
        try {
          artifact = client.execute(request("/framing", body, 2));
          assertEquals(200, artifact.statusCode());
          assertArrayEquals(new byte[] {'o', 'k'}, read(artifact));

          double durationSeconds =
              metric(
                  metrics.prometheus(new OriginExecutorStats(0, 0, 0, 0), 0, 0, 1, 0, 0, 0),
                  "bounded_origin_origin_duration_seconds_sum");
          assertTrue(durationSeconds >= 0.0d);
          assertTrue(durationSeconds < 5.0d);
        } finally {
          body.close();
          deleteTemporary(artifact);
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void responseLimitAcceptsExactLengthAndRejectsOneByteOver() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/exact-limit",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nhey");
            return true;
          });
      origin.respond(
          "/over-limit",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: keep-alive\r\n\r\nover");
            return true;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota)) {
        StreamingSpool.Result body = body(new byte[0], 0);
        Artifact exact = null;
        try {
          exact = client.execute(request("/exact-limit", body, 3));
          assertEquals(3, exact.contentLength());
          assertArrayEquals(new byte[] {'h', 'e', 'y'}, read(exact));

          MaterializationException failure =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("/over-limit", body, 3)));
          assertTrue(failure.getMessage().contains("configured limit"));
          assertInstanceOf(StreamingSpool.BodyLimitExceededException.class, failure.getCause());
        } finally {
          body.close();
          deleteTemporary(exact);
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void zeroLengthBodyForbiddenResponseIsAccepted() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/no-content",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[0], 0);
        Artifact artifact = null;
        try {
          artifact = client.execute(request("/no-content", body, 1));
          assertEquals(204, artifact.statusCode());
          assertEquals(0, artifact.contentLength());
        } finally {
          body.close();
          deleteTemporary(artifact);
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void responseTimeoutMapsPreciselyAndInvalidatesTheConnection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/timeout",
          (request, socket) -> {
            entered.countDown();
            TestOriginServer.await(release, Duration.ofSeconds(2));
            return false;
          });

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.response-timeout", "PT0.05S", "request.timeout", "PT1S"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client =
          new NettyOriginClient(group, config, new GatewayMetrics(), quota)) {
        StreamingSpool.Result body = body(new byte[0], 0);
        try {
          MaterializationException failure =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("/timeout", body, 16)));
          assertTrue(entered.await(1, TimeUnit.SECONDS));
          assertEquals("origin response timed out", failure.getMessage());
          assertInstanceOf(TimeoutException.class, failure.getCause());
          awaitConnections(client, 0, Duration.ofSeconds(1));
        } finally {
          body.close();
        }
      } finally {
        release.countDown();
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    } finally {
      release.countDown();
    }
  }

  private OriginRequest request(String target, StreamingSpool.Result body, long maxResponseBytes) {
    return new OriginRequest(
        "POST", target, Map.of("host", List.of("example.test")), body, maxResponseBytes);
  }

  private StreamingSpool.Result body(byte[] bytes, long declaredLength) throws Exception {
    Path path = Files.createTempFile(temporaryDirectory, "origin-boundary-request-", ".tmp");
    Files.write(path, bytes);
    SpoolQuota quota = new SpoolQuota(4096, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, declaredLength, "digest", reservation);
  }

  private static byte[] read(Artifact artifact) throws Exception {
    try (InputStream input = artifact.body().openStream()) {
      return input.readAllBytes();
    }
  }

  private static double metric(String prometheus, String name) {
    String prefix = name + " ";
    for (String line : prometheus.lines().toList()) {
      if (line.startsWith(prefix)) {
        return Double.parseDouble(line.substring(prefix.length()));
      }
    }
    throw new AssertionError("missing metric " + name);
  }

  private static void awaitConnections(
      NettyOriginClient client, int expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (client.openConnections() == expected) {
        return;
      }
      Thread.sleep(5);
    }
    assertEquals(expected, client.openConnections());
  }

  private static void deleteTemporary(Artifact artifact) {
    if (artifact != null && artifact.body() instanceof TemporaryArtifactBody body) {
      body.delete();
    }
  }
}
