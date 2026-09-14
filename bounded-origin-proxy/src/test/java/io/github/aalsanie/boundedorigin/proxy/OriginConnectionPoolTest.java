package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class OriginConnectionPoolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void reusesConnectionsAndBoundsPendingAcquires() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.max-connections", "1", "origin.max-pending-acquires", "1"));
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool = new OriginConnectionPool(group, config, metrics)) {
        OriginConnectionPool.Lease first =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(1, pool.openConnections());
        var pending = pool.acquire().toCompletableFuture();
        assertEquals(1, pool.pendingAcquires());
        var rejected = pool.acquire().toCompletableFuture();
        CompletionException rejection = assertThrows(CompletionException.class, rejected::join);
        assertTrue(rejection.getCause() instanceof OriginConnectionPool.PoolExhaustedException);

        first.close();
        OriginConnectionPool.Lease second = pending.get(2, TimeUnit.SECONDS);
        assertSame(first.channel(), second.channel());
        second.close();

        OriginConnectionPool.Lease third =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertSame(first.channel(), third.channel());
        third.invalidate();
        third.close();
        awaitConnections(pool, 0, Duration.ofSeconds(2));
        assertEquals(1, metrics.snapshot().originPoolRejections());
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void cancelledPendingAcquireDoesNotConsumePoolCapacity() throws Exception {
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
        var cancelled = pool.acquire().toCompletableFuture();
        assertTrue(cancelled.cancel(false));
        first.close();

        OriginConnectionPool.Lease next =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertSame(first.channel(), next.channel());
        next.close();
        assertEquals(0, pool.pendingAcquires());
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void closeFailsWaitingAndFutureAcquires() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of("origin.max-connections", "1", "origin.max-pending-acquires", "1"));
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      OriginConnectionPool pool = new OriginConnectionPool(group, config, new GatewayMetrics());
      try {
        OriginConnectionPool.Lease first =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        var waiting = pool.acquire().toCompletableFuture();
        pool.close();
        assertThrows(CompletionException.class, waiting::join);
        assertThrows(CompletionException.class, () -> pool.acquire().toCompletableFuture().join());
        first.close();
      } finally {
        pool.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private static void awaitConnections(OriginConnectionPool pool, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (pool.openConnections() == expected) {
        return;
      }
      Thread.sleep(10);
    }
    assertEquals(expected, pool.openConnections());
  }
}
