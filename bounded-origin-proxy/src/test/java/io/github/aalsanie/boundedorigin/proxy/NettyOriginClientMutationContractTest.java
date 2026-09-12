package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NettyOriginClientMutationContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void successfulExchangeStreamsChunkBoundariesAndRecordsObservableState() throws Exception {
    byte[] requestBytes = new byte[] {1, 2, 3, 4, 5};
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/chunk-boundary",
          (request, socket) -> {
            assertArrayEquals(requestBytes, request.body());
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nhey");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("http.chunk-bytes", "2"));
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota)) {
        StreamingSpool.Result body = body(requestBytes);
        Artifact first = null;
        Artifact second = null;
        try {
          first = client.execute(request("/chunk-boundary", body));
          assertEquals(200, first.statusCode());
          assertArrayEquals("hey".getBytes(java.nio.charset.StandardCharsets.UTF_8), read(first));
          assertEquals(1, client.openConnections());

          second = client.execute(request("/chunk-boundary", body));
          assertEquals(200, second.statusCode());
          assertArrayEquals("hey".getBytes(java.nio.charset.StandardCharsets.UTF_8), read(second));
          assertEquals(1, client.openConnections());
          assertEquals(1, origin.connections());
          assertEquals(2, origin.requests());

          String prometheus =
              metrics.prometheus(new OriginExecutorStats(0, 0, 0, 0), 0, 0, 1, 0, 0, 0);
          assertTrue(prometheus.contains("bounded_origin_origin_duration_seconds_count 2\n"));
          assertTrue(prometheus.contains("bounded_origin_origin_response_bytes_total 6\n"));
        } finally {
          body.close();
          deleteTemporary(first);
          deleteTemporary(second);
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void closingTheClientClosesItsPoolAndRejectsFurtherAcquires() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/closed", 200, "ok");
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota);
      try {
        client.close();
        StreamingSpool.Result body = body(new byte[0]);
        try {
          MaterializationException failure =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("/closed", body)));
          assertTrue(failure.getMessage().contains("origin connection acquisition failed"));
        } finally {
          body.close();
        }
        assertEquals(0, origin.requests());
      } finally {
        client.close();
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void acquisitionTimeoutIsCountedAndCancelsThePendingAcquire() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/hold",
          (request, socket) -> {
            firstEntered.countDown();
            assertTrue(TestOriginServer.await(releaseFirst, Duration.ofSeconds(3)));
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "1"),
                  Map.entry("origin.acquire-timeout", "PT0.1S"),
                  Map.entry("origin.response-timeout", "PT2S")));
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota)) {
        StreamingSpool.Result firstBody = body(new byte[0]);
        StreamingSpool.Result secondBody = body(new byte[0]);
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
        try {
          assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
          MaterializationException timeout =
              assertThrows(
                  MaterializationException.class,
                  () -> client.execute(request("/never-sent", secondBody)));
          assertTrue(timeout.getMessage().contains("acquisition timed out"));
          assertEquals(1, metrics.snapshot().originPoolRejections());
          assertEquals(0, client.pendingAcquires());
        } finally {
          releaseFirst.countDown();
          assertTrue(first.join(Duration.ofSeconds(2)));
          firstBody.close();
          secondBody.close();
          deleteTemporary(firstArtifact.get());
        }
        assertEquals(null, firstFailure.get());
      } finally {
        releaseFirst.countDown();
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private OriginRequest request(String target, StreamingSpool.Result body) {
    return new OriginRequest("POST", target, Map.of("host", List.of("example.test")), body, 1024);
  }

  private StreamingSpool.Result body(byte[] bytes) throws Exception {
    Path path = Files.createTempFile(temporaryDirectory, "origin-mutation-request-", ".tmp");
    Files.write(path, bytes);
    SpoolQuota quota = new SpoolQuota(4096, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, bytes.length, "digest", reservation);
  }

  private static byte[] read(Artifact artifact) throws Exception {
    try (InputStream input = artifact.body().openStream()) {
      return input.readAllBytes();
    }
  }

  private static void deleteTemporary(Artifact artifact) {
    if (artifact != null && artifact.body() instanceof TemporaryArtifactBody body) {
      body.delete();
    }
  }
}
