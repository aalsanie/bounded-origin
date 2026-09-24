package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginAcquisitionOwnershipTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abandonmentConservesTheOnlyConnectionInBothDeliveryOrders(boolean deliveryFirst)
      throws IOException,
          ReflectiveOperationException,
          InterruptedException,
          ExecutionException,
          TimeoutException {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = config(origin.port(), 1);
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool =
          new OriginConnectionPool(group, config, new GatewayMetrics())) {
        OriginConnectionPool.Lease held = borrow(pool);
        try {
          var abandoned = pool.acquire().toCompletableFuture();
          assertFalse(abandoned.isDone());
          if (deliveryFirst) {
            held.close();
            assertTrue(abandoned.isDone());
          }
          abandon(abandoned);
          if (!deliveryFirst) {
            assertTrue(abandoned.isCancelled());
            held.close();
          }

          var replacement = pool.acquire().toCompletableFuture();
          assertTrue(replacement.isDone(), "abandonment must reclaim the delivered lease");
          OriginConnectionPool.Lease next = replacement.join();
          try {
            assertSame(held.channel(), next.channel());
            assertEquals(0, pool.pendingAcquires());
            abandon(abandoned);
            var waiting = pool.acquire().toCompletableFuture();
            assertFalse(waiting.isDone(), "repeated abandonment must not release another owner");
            assertEquals(1, pool.pendingAcquires());
            next.close();
            assertTrue(waiting.isDone());
            try (OriginConnectionPool.Lease last = waiting.join()) {
              assertSame(held.channel(), last.channel());
              assertEquals(1, pool.openConnections());
              assertEquals(0, pool.pendingAcquires());
            }
          } finally {
            next.close();
          }
        } finally {
          held.close();
        }
        assertEquals(0, origin.requests());
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void reclaimingDeliveredLeaseServesAnAlreadyWaitingAcquisition()
      throws IOException,
          ReflectiveOperationException,
          InterruptedException,
          ExecutionException,
          TimeoutException {
    try (TestOriginServer origin = new TestOriginServer()) {
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      try (OriginConnectionPool pool =
          new OriginConnectionPool(group, config(origin.port(), 2), new GatewayMetrics())) {
        OriginConnectionPool.Lease held = borrow(pool);
        try {
          var abandoned = pool.acquire().toCompletableFuture();
          var waiting = pool.acquire().toCompletableFuture();
          assertEquals(2, pool.pendingAcquires());
          held.close();
          assertTrue(abandoned.isDone());
          assertFalse(waiting.isDone());
          abandon(abandoned);
          assertTrue(waiting.isDone(), "reclaimed capacity must reach the next waiter");
          try (OriginConnectionPool.Lease next = waiting.join()) {
            assertSame(held.channel(), next.channel());
            assertEquals(0, pool.pendingAcquires());
            assertEquals(1, pool.openConnections());
          }
        } finally {
          held.close();
        }
      } finally {
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void abandonmentAfterShutdownHandlesFailedAndDeliveredAcquisitions(boolean deliveryFirst)
      throws IOException,
          ReflectiveOperationException,
          InterruptedException,
          ExecutionException,
          TimeoutException {
    try (TestOriginServer origin = new TestOriginServer()) {
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      OriginConnectionPool pool =
          new OriginConnectionPool(group, config(origin.port(), 1), new GatewayMetrics());
      try {
        OriginConnectionPool.Lease held = borrow(pool);
        try {
          var abandoned = pool.acquire().toCompletableFuture();
          if (deliveryFirst) {
            held.close();
          }
          pool.close();
          abandon(abandoned);
          abandon(abandoned);
          assertTrue(held.channel().closeFuture().await(2, TimeUnit.SECONDS));
          assertEquals(0, pool.pendingAcquires());
          CompletionException rejected =
              assertThrows(
                  CompletionException.class, () -> pool.acquire().toCompletableFuture().join());
          assertInstanceOf(OriginConnectionPool.PoolClosedException.class, rejected.getCause());
          if (!deliveryFirst) {
            CompletionException failed = assertThrows(CompletionException.class, abandoned::join);
            assertInstanceOf(OriginConnectionPool.PoolClosedException.class, failed.getCause());
          }
        } finally {
          held.close();
        }
      } finally {
        pool.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  @Test
  void interruptedClientAcquisitionPreservesInterruptAndNeverDispatchesOriginWork()
      throws IOException,
          ReflectiveOperationException,
          InterruptedException,
          ExecutionException,
          TimeoutException {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = config(origin.port(), 1);
      GatewayMetrics metrics = new GatewayMetrics();
      EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
      SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
      try (NettyOriginClient client = new NettyOriginClient(group, config, metrics, quota);
          StreamingSpool spool = new StreamingSpool(temporaryDirectory, "request-", 1024, quota);
          StreamingSpool.Result body =
              spool.finish().toCompletableFuture().get(2, TimeUnit.SECONDS)) {
        Field field = NettyOriginClient.class.getDeclaredField("pool");
        field.setAccessible(true);
        OriginConnectionPool pool = (OriginConnectionPool) field.get(client);
        AtomicInteger admissions = new AtomicInteger();
        OriginRequest request =
            new OriginRequest(
                "GET", "/never-sent", Map.of("host", List.of("example.test")), body, 1024);
        OriginConnectionPool.Lease held = borrow(pool);
        try {
          Thread.currentThread().interrupt();
          try {
            MaterializationException failure =
                assertThrows(
                    MaterializationException.class,
                    () ->
                        client.execute(
                            request,
                            () -> {
                              admissions.incrementAndGet();
                              throw new AssertionError(
                                  "an interrupted acquisition cannot admit work");
                            }));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
          } finally {
            Thread.interrupted();
          }
          assertEquals(0, client.pendingAcquires());
          assertEquals(0, admissions.get());
          assertEquals(0, origin.requests());
          assertEquals(0, metrics.snapshot().originPoolRejections());
          held.close();
          try (OriginConnectionPool.Lease next = borrow(pool)) {
            assertSame(held.channel(), next.channel());
          }
        } finally {
          held.close();
        }
      } finally {
        quota.close();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  private GatewayConfig config(int originPort, int pending) {
    return GatewayTestFixtures.config(
        originPort,
        temporaryDirectory,
        Map.of(
            "origin.max-connections",
            "1",
            "origin.max-pending-acquires",
            Integer.toString(pending)));
  }

  private static OriginConnectionPool.Lease borrow(OriginConnectionPool pool)
      throws InterruptedException, ExecutionException, TimeoutException {
    return pool.acquire().toCompletableFuture().get(2, TimeUnit.SECONDS);
  }

  private static void abandon(CompletableFuture<OriginConnectionPool.Lease> acquisition)
      throws ReflectiveOperationException {
    Method method =
        NettyOriginClient.class.getDeclaredMethod("abandonAcquisition", CompletableFuture.class);
    method.setAccessible(true);
    method.invoke(null, acquisition);
  }
}
