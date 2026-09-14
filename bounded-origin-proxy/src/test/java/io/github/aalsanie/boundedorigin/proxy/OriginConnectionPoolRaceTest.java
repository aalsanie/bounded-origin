package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginConnectionPoolRaceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void closingPoolBeforeQueuedConnectCompletesRejectsConnectedChannel() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = config(origin.port());
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      OriginConnectionPool pool = new OriginConnectionPool(group, config, new GatewayMetrics());
      EventLoopGate gate = blockEventLoop(group);
      try {
        CompletableFuture<OriginConnectionPool.Lease> acquire =
            pool.acquire().toCompletableFuture();
        pool.close();
        gate.open();

        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> acquire.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof OriginConnectionPool.PoolClosedException);
        awaitConnections(pool, 0);
      } finally {
        gate.open();
        pool.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void cancelledDirectAcquireReturnsSuccessfulConnectionToPool() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = config(origin.port());
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool =
          new OriginConnectionPool(group, config, new GatewayMetrics())) {
        EventLoopGate gate = blockEventLoop(group);
        CompletableFuture<OriginConnectionPool.Lease> cancelled =
            pool.acquire().toCompletableFuture();
        assertTrue(cancelled.cancel(false));
        gate.open();

        awaitConnections(pool, 1);
        OriginConnectionPool.Lease next =
            pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
        try {
          assertTrue(next.channel().isActive());
        } finally {
          next.close();
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void failedConnectPromotesPendingAcquireInsteadOfLeakingReservation() throws Exception {
    GatewayConfig config = config(GatewayTestFixtures.unusedPort());
    EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try (OriginConnectionPool pool =
        new OriginConnectionPool(group, config, new GatewayMetrics())) {
      EventLoopGate gate = blockEventLoop(group);
      CompletableFuture<OriginConnectionPool.Lease> first = pool.acquire().toCompletableFuture();
      CompletableFuture<OriginConnectionPool.Lease> second = pool.acquire().toCompletableFuture();
      assertEquals(1, pool.pendingAcquires());

      gate.open();

      assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
      awaitConnections(pool, 0);
      assertEquals(0, pool.pendingAcquires());
    } finally {
      group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  @Test
  void closingPoolBeforeQueuedConnectFailureDoesNotPromotePendingWork() throws Exception {
    GatewayConfig config = config(GatewayTestFixtures.unusedPort());
    EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    OriginConnectionPool pool = new OriginConnectionPool(group, config, new GatewayMetrics());
    EventLoopGate gate = blockEventLoop(group);
    try {
      CompletableFuture<OriginConnectionPool.Lease> acquire = pool.acquire().toCompletableFuture();
      pool.close();
      gate.open();

      assertThrows(ExecutionException.class, () -> acquire.get(2, TimeUnit.SECONDS));
      awaitConnections(pool, 0);
      assertEquals(0, pool.pendingAcquires());
    } finally {
      gate.open();
      pool.close();
      group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  private GatewayConfig config(int originPort) throws Exception {
    return GatewayTestFixtures.config(
        originPort,
        temporaryDirectory,
        Map.of(
            "origin.max-connections", "1",
            "origin.max-pending-acquires", "1",
            "origin.connect-timeout", "PT0.5S"));
  }

  private static EventLoopGate blockEventLoop(EventLoopGroup group) throws InterruptedException {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    group
        .next()
        .execute(
            () -> {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                  return;
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
            });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    return new EventLoopGate(release);
  }

  private static void awaitConnections(OriginConnectionPool pool, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (pool.openConnections() == expected) {
        return;
      }
      Thread.sleep(5);
    }
    assertEquals(expected, pool.openConnections());
  }

  private record EventLoopGate(CountDownLatch release) {
    void open() {
      release.countDown();
    }
  }
}
