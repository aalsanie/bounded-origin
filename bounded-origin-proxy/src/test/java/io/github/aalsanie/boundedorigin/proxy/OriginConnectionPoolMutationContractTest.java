package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginConnectionPoolMutationContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void replacementConnectionStillConsumesExactlyOneReservation() throws Exception {
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
            pool.acquire().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var replacementFuture = pool.acquire().toCompletableFuture();
        assertEquals(1, pool.pendingAcquires());

        first.channel().close().syncUninterruptibly();
        OriginConnectionPool.Lease replacement = replacementFuture.get(1, TimeUnit.SECONDS);
        try {
          awaitConnections(origin, 2, Duration.ofSeconds(1));
          assertEquals(1, pool.openConnections());

          var thirdFuture = pool.acquire().toCompletableFuture();
          assertFalse(thirdFuture.isDone());
          assertEquals(1, pool.pendingAcquires());
          Thread.sleep(50);
          assertEquals(2, origin.connections());

          replacement.close();
          OriginConnectionPool.Lease third = thirdFuture.get(1, TimeUnit.SECONDS);
          try {
            assertSame(replacement.channel(), third.channel());
            assertEquals(1, pool.openConnections());
          } finally {
            third.close();
          }
        } finally {
          replacement.close();
          first.close();
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void completedPendingAcquireIsPrunedBeforeCapacityDecision() throws Exception {
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
            pool.acquire().toCompletableFuture().get(1, TimeUnit.SECONDS);
        var cancelled = pool.acquire().toCompletableFuture();
        assertTrue(cancelled.cancel(false));

        var replacement = pool.acquire().toCompletableFuture();
        assertFalse(replacement.isDone());
        assertEquals(1, pool.pendingAcquires());

        first.close();
        OriginConnectionPool.Lease second = replacement.get(1, TimeUnit.SECONDS);
        second.close();
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private static void awaitConnections(TestOriginServer origin, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (origin.connections() != expected && System.nanoTime() - deadline < 0) {
      Thread.sleep(5);
    }
    assertEquals(expected, origin.connections());
  }
}
