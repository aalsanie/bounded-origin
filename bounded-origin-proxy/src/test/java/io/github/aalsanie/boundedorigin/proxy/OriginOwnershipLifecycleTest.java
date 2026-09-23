package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginOwnershipLifecycleTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queuedDistinctOperationsCannotReplaceUnresolvedWork(boolean materialize) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch reset = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/one",
          (request, socket) -> {
            active.incrementAndGet();
            entered.countDown();
            await(reset);
            socket.close();
            try {
              await(finish);
            } finally {
              active.decrementAndGet();
            }
            return false;
          });
      GatewayConfig config = config(origin.port(), 2);
      try (BoundedOriginGateway gateway = start(config, materialize);
          RawHttpClient first = new RawHttpClient(gateway.listenAddress());
          RawHttpClient second = new RawHttpClient(gateway.listenAddress());
          RawHttpClient third = new RawHttpClient(gateway.listenAddress())) {
        send(first, "/one");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        send(second, "/two");
        send(third, "/three");
        awaitMetric(gateway, "origin_queue_depth", 2);
        reset.countDown();
        assertEquals(502, first.readResponse("GET").status());
        assertEquals(503, second.readResponse("GET").status());
        assertEquals(503, third.readResponse("GET").status());
        assertEquals(1, active.get());
        assertEquals(1, origin.requests());
        awaitMetric(gateway, "origin_work_unresolved", 1);
        awaitMetric(gateway, "origin_queue_depth", 0);
      }
    } finally {
      reset.countDown();
      finish.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void gracefulShutdownRetainsOwnershipForTheNextGateway(boolean materialize) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/one",
          (request, socket) -> {
            active.incrementAndGet();
            entered.countDown();
            try {
              await(finish);
            } finally {
              active.decrementAndGet();
            }
            return false;
          });
      GatewayConfig config = config(origin.port(), 0);
      try (BoundedOriginGateway gateway = start(config, materialize);
          RawHttpClient first = new RawHttpClient(gateway.listenAddress())) {
        send(first, "/one");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
      }
      assertEquals(1, active.get());
      try (BoundedOriginGateway restarted = start(config, materialize);
          RawHttpClient request = new RawHttpClient(restarted.listenAddress())) {
        assertEquals(
            503,
            request.request("GET", "/two", Map.of("Host", "example.test"), new byte[0]).status());
        assertEquals(1, origin.requests());
        assertEquals(1, active.get());
        awaitMetric(restarted, "origin_work_unresolved", 1);
      }
    } finally {
      finish.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void downstreamDeadlineDetachesOnlyTheCallerAndLateWorkRemainsSingleFlight(boolean materialize)
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/one",
          (request, socket) -> {
            active.incrementAndGet();
            entered.countDown();
            try {
              await(finish);
            } finally {
              active.decrementAndGet();
            }
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nCache-Control: public\r\nContent-Length: 2\r\n\r\nok");
            return true;
          });
      GatewayConfig config = config(origin.port(), 0);
      try (BoundedOriginGateway gateway = start(config, materialize);
          RawHttpClient first = new RawHttpClient(gateway.listenAddress())) {
        send(first, "/one");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        triggerOwnedRequestDeadline(gateway);
        assertEquals(408, first.readResponse("GET").status());
        assertEquals(1, active.get());
        awaitMetric(gateway, "origin_work_outstanding", 1);
        awaitMetric(gateway, "origin_work_unresolved", 0);
        try (RawHttpClient follower = new RawHttpClient(gateway.listenAddress())) {
          send(follower, "/one");
          awaitMetric(gateway, "single_flight_joins_total", 1);
          finish.countDown();
          assertEquals(200, follower.readResponse("GET").status());
        }
        assertEquals(1, origin.requests());
        assertEquals(0, active.get());
        awaitMetric(gateway, "origin_work_outstanding", 0);
        awaitMetric(gateway, "spool_files", 0);
      }
    } finally {
      finish.countDown();
    }
  }

  private GatewayConfig config(int port, int queued) {
    return GatewayTestFixtures.config(
        port,
        directory,
        Map.of(
            "origin.max-active",
            "1",
            "origin.max-queued",
            Integer.toString(queued),
            "origin.max-connections",
            "1",
            "origin.max-pending-acquires",
            "0",
            "origin.response-timeout",
            "PT10S",
            "origin.max-execution-duration",
            "PT10S",
            "request.timeout",
            "PT15S",
            "drain.timeout",
            "PT0.1S"));
  }

  private static BoundedOriginGateway start(GatewayConfig config, boolean materialize)
      throws Exception {
    OriginPolicy policy =
        materialize
            ? GatewayTestFixtures.materializePolicy(config.globalBudget())
            : GatewayTestFixtures.boundedPolicy(config.globalBudget());
    return GatewayTestFixtures.start(
        config, GatewayTestFixtures.engine(policy), new GatewayTestFixtures.MemoryArtifactStore());
  }

  private static void send(RawHttpClient client, String target) throws Exception {
    client.write("GET " + target + " HTTP/1.1\r\nHost: example.test\r\n\r\n");
  }

  private static void await(CountDownLatch latch) throws java.io.IOException {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new java.io.IOException("phase did not complete");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new java.io.IOException("origin phase interrupted", exception);
    }
  }

  private static void awaitMetric(BoundedOriginGateway gateway, String metric, long expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    long actual;
    do {
      actual = metric(gateway.adminAddress(), metric);
      if (actual == expected) {
        return;
      }
    } while (System.nanoTime() < deadline);
    assertEquals(expected, actual, metric);
  }

  private static long metric(InetSocketAddress address, String name) throws Exception {
    try (RawHttpClient admin = new RawHttpClient(address)) {
      String output =
          admin.request("GET", "/metrics", Map.of("Host", "localhost"), new byte[0]).bodyText();
      String prefix = "bounded_origin_" + name + " ";
      for (String line : output.split("\\R")) {
        if (line.startsWith(prefix)) {
          return Long.parseLong(line.substring(prefix.length()));
        }
      }
      throw new AssertionError("missing metric " + name);
    }
  }

  private static void triggerOwnedRequestDeadline(BoundedOriginGateway gateway) throws Exception {
    Field runtimeField = BoundedOriginGateway.class.getDeclaredField("runtime");
    runtimeField.setAccessible(true);
    GatewayRuntimeState runtime = (GatewayRuntimeState) runtimeField.get(gateway);
    Channel channel = runtime.clients().iterator().next();
    channel
        .eventLoop()
        .submit(
            () -> {
              try {
                GatewayRequestHandler handler = channel.pipeline().get(GatewayRequestHandler.class);
                Field current = GatewayRequestHandler.class.getDeclaredField("current");
                current.setAccessible(true);
                Object state = current.get(handler);
                Field timer = state.getClass().getDeclaredField("timeout");
                timer.setAccessible(true);
                ((java.util.concurrent.Future<?>) timer.get(state)).cancel(false);
                timer.set(state, null);
                Method deadline =
                    GatewayRequestHandler.class.getDeclaredMethod(
                        "requestTimedOut", ChannelHandlerContext.class, state.getClass());
                deadline.setAccessible(true);
                deadline.invoke(handler, channel.pipeline().context(handler), state);
              } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
              }
            })
        .sync();
  }
}
