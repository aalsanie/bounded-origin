package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginConnectionPoolFailureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void failedConnectReleasesReservationForLaterAcquires() throws Exception {
    int unusedPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config =
        GatewayTestFixtures.config(
            unusedPort,
            temporaryDirectory,
            Map.of(
                "origin.max-connections", "1",
                "origin.max-pending-acquires", "0",
                "origin.connect-timeout", "PT0.2S"));
    EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try (OriginConnectionPool pool =
        new OriginConnectionPool(group, config, new GatewayMetrics())) {
      assertConnectFailure(pool);
      awaitConnections(pool, 0, Duration.ofSeconds(2));
      assertConnectFailure(pool);
      awaitConnections(pool, 0, Duration.ofSeconds(2));
      assertEquals(0, pool.pendingAcquires());
    } finally {
      group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  @Test
  void closedActiveChannelCreatesReplacementForPendingAcquire() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.max-connections", "1", "origin.max-pending-acquires", "1"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool =
          new OriginConnectionPool(group, config, new GatewayMetrics())) {
        OriginConnectionPool.Lease first =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        var waiting = pool.acquire().toCompletableFuture();
        assertEquals(1, pool.pendingAcquires());

        first.channel().close().syncUninterruptibly();
        OriginConnectionPool.Lease replacement = waiting.get(2, TimeUnit.SECONDS);
        try {
          assertNotSame(first.channel(), replacement.channel());
          assertTrue(replacement.channel().isActive());
        } finally {
          replacement.close();
          first.close();
          first.close();
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void idleReturnedConnectionIsClosedByOriginIdleTimeout() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("idle.timeout", "PT0.05S"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool =
          new OriginConnectionPool(group, config, new GatewayMetrics())) {
        OriginConnectionPool.Lease lease =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        lease.close();
        awaitConnections(pool, 0, Duration.ofSeconds(2));
        assertEquals(0, pool.openConnections());
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void connectTimeoutLongerThanIntegerMillisecondsIsClamped() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("origin.connect-timeout", "PT720H"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool =
          new OriginConnectionPool(group, config, new GatewayMetrics())) {
        assertTrue(config.originConnectTimeout().toMillis() > Integer.MAX_VALUE);
        assertEquals(0, pool.openConnections());
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private static void assertConnectFailure(OriginConnectionPool pool) {
    CompletionException failure =
        assertThrows(CompletionException.class, () -> pool.acquire().toCompletableFuture().join());
    assertTrue(failure.getCause() != null);
  }

  private static void awaitConnections(OriginConnectionPool pool, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (pool.openConnections() == expected) {
        return;
      }
      Thread.sleep(5);
    }
    assertEquals(expected, pool.openConnections());
  }
}
