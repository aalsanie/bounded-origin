package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayHandlerTerminalRaceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void failedContinueWriteTerminatesActiveRequest() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.FAIL_CONTINUE);
    try (Fixture fixture = fixture(outbound, false)) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/continue-failure");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);

      fixture.channel().writeInbound(request);
      assertTrue(outbound.awaitIntercept(fixture.channel()));
      fixture.channel().runPendingTasks();

      assertFalse(fixture.channel().isActive());
      assertEquals(0, fixture.runtime().activeRequests());
    }
  }

  @Test
  void successfulContinueCompletionAfterTimeoutDoesNotReviveRequest() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.DEFER_CONTINUE);
    try (Fixture fixture = fixture(outbound, false)) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/continue-timeout");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);

      fixture.channel().writeInbound(request);
      assertTrue(outbound.awaitIntercept(fixture.channel()));
      fireRequestTimeout(fixture);
      assertStatus(fixture.channel(), 408);
      awaitNoActiveRequests(fixture.channel(), fixture.runtime());

      outbound.succeedDeferred();
      fixture.channel().runPendingTasks();
      assertEquals(0, fixture.runtime().activeRequests());
    }
  }

  @Test
  void requestTimeoutAfterResponseStartedTerminatesOnlyOnce() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.DEFER_FINAL);
    try (Fixture fixture = fixture(outbound, true)) {
      completeGet(fixture.channel(), "/deferred-response");
      assertTrue(outbound.awaitIntercept(fixture.channel()));

      fireRequestTimeout(fixture);
      awaitInactive(fixture.channel());
      awaitNoActiveRequests(fixture.channel(), fixture.runtime());

      outbound.failDeferred();
      fixture.channel().runPendingTasks();
      awaitNoActiveRequests(fixture.channel(), fixture.runtime());
    }
  }

  @Test
  void failedFinalResponseWriteTerminatesRequest() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.FAIL_FINAL);
    try (Fixture fixture = fixture(outbound, true)) {
      completeGet(fixture.channel(), "/failed-response");
      assertTrue(outbound.awaitIntercept(fixture.channel()));
      awaitInactive(fixture.channel());
      awaitNoActiveRequests(fixture.channel(), fixture.runtime());

      assertEquals(0, fixture.runtime().activeRequests());
    }
  }

  private Fixture fixture(ControlledOutbound outbound, boolean storedArtifact) throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of(
                "origin.max-execution-duration", "PT0.04S",
                "origin.response-timeout", "PT0.03S",
                "request.timeout", "PT3S"));
    GatewayMetrics metrics = new GatewayMetrics();
    GatewayRuntimeState runtime = new GatewayRuntimeState(config.maxClientConnections());
    runtime.started();
    SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    EventLoopGroup originGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            config.globalBudget(), config.failureCooldown(), config.maxCooldownEntries());
    NettyOriginClient originClient = new NettyOriginClient(originGroup, config, metrics, quota);
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    if (storedArtifact) {
      store.defaultArtifact(GatewayTestFixtures.artifact(200, "ok"));
    }
    FlightLeaseRegistry flights = new FlightLeaseRegistry(metrics);
    GatewayRequestProcessor processor =
        new GatewayRequestProcessor(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
            store,
            executor,
            originClient,
            metrics,
            flights);
    GatewayRequestHandler handler =
        new GatewayRequestHandler(
            config,
            processor,
            new ArtifactResponseWriter(config, metrics),
            metrics,
            runtime,
            quota);
    EmbeddedChannel channel = new EmbeddedChannel(outbound, handler);
    ChannelHandlerContext handlerContext = channel.pipeline().context(handler);
    if (handlerContext == null) {
      throw new AssertionError("gateway handler context was not installed");
    }
    return new Fixture(
        channel,
        outbound,
        handler,
        handlerContext,
        runtime,
        quota,
        executor,
        originClient,
        originGroup);
  }

  private static DefaultHttpRequest request(HttpMethod method, String target) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, target);
    request.headers().set(HttpHeaderNames.HOST, "example.test");
    return request;
  }

  private static void completeGet(EmbeddedChannel channel, String path) {
    DefaultHttpRequest request = request(HttpMethod.GET, path);
    request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
    channel.writeInbound(request);
    channel.writeInbound(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
  }

  private static void fireRequestTimeout(Fixture fixture) throws Exception {
    Field currentField = GatewayRequestHandler.class.getDeclaredField("current");
    currentField.setAccessible(true);
    Object state = currentField.get(fixture.handler());
    if (state == null) {
      throw new AssertionError("request state was not created");
    }

    Method timeout =
        GatewayRequestHandler.class.getDeclaredMethod(
            "requestTimedOut", ChannelHandlerContext.class, state.getClass());
    timeout.setAccessible(true);

    timeout.invoke(fixture.handler(), fixture.handlerContext(), state);
    fixture.channel().runPendingTasks();
  }

  private static void assertStatus(EmbeddedChannel channel, int expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      channel.runPendingTasks();
      channel.runScheduledPendingTasks();
      Object outbound = channel.readOutbound();
      if (outbound != null) {
        try {
          if (outbound instanceof HttpResponse response) {
            assertEquals(expected, response.status().code());
            return;
          }
        } finally {
          ReferenceCountUtil.release(outbound);
        }
      }
      Thread.sleep(1);
    }
    throw new AssertionError("response was not written");
  }

  private static void awaitNoActiveRequests(EmbeddedChannel channel, GatewayRuntimeState runtime)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (runtime.activeRequests() != 0 && System.nanoTime() - deadline < 0) {
      channel.runPendingTasks();
      channel.runScheduledPendingTasks();
      Thread.sleep(1);
    }
    assertEquals(0, runtime.activeRequests());
  }

  private static void awaitInactive(EmbeddedChannel channel) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (channel.isActive() && System.nanoTime() - deadline < 0) {
      channel.runPendingTasks();
      Thread.sleep(1);
    }
    assertFalse(channel.isActive());
  }

  private enum Mode {
    FAIL_CONTINUE,
    DEFER_CONTINUE,
    FAIL_FINAL,
    DEFER_FINAL
  }

  private static final class ControlledOutbound extends ChannelOutboundHandlerAdapter {
    private final Mode mode;
    private final CountDownLatch intercepted = new CountDownLatch(1);
    private ChannelPromise deferred;

    private ControlledOutbound(Mode mode) {
      this.mode = mode;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      if (shouldIntercept(message)) {
        ReferenceCountUtil.release(message);
        intercepted.countDown();
        if (mode == Mode.FAIL_CONTINUE || mode == Mode.FAIL_FINAL) {
          promise.setFailure(new IOException("controlled downstream failure"));
        } else {
          deferred = promise;
        }
        return;
      }
      context.write(message, promise);
    }

    private boolean shouldIntercept(Object message) {
      return switch (mode) {
        case FAIL_CONTINUE, DEFER_CONTINUE ->
            message instanceof HttpResponse response && response.status().code() == 100;
        case FAIL_FINAL ->
            message instanceof HttpResponse response && response.status().code() != 100;
        case DEFER_FINAL -> message instanceof LastHttpContent;
      };
    }

    private boolean awaitIntercept(EmbeddedChannel channel) throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (System.nanoTime() - deadline < 0) {
        channel.runPendingTasks();
        if (intercepted.await(1, TimeUnit.MILLISECONDS)) {
          return true;
        }
      }
      return false;
    }

    private void succeedDeferred() {
      ChannelPromise promise = deferred;
      if (promise != null) {
        promise.trySuccess();
        deferred = null;
      }
    }

    private void failDeferred() {
      ChannelPromise promise = deferred;
      if (promise != null) {
        promise.tryFailure(new IOException("late downstream failure"));
        deferred = null;
      }
    }
  }

  private record Fixture(
      EmbeddedChannel channel,
      ControlledOutbound outbound,
      GatewayRequestHandler handler,
      ChannelHandlerContext handlerContext,
      GatewayRuntimeState runtime,
      SpoolQuota quota,
      BoundedOriginExecutor executor,
      NettyOriginClient originClient,
      EventLoopGroup originGroup)
      implements AutoCloseable {
    @Override
    public void close() {
      outbound.failDeferred();
      channel.runPendingTasks();
      channel.finishAndReleaseAll();
      runtime.closed();
      originClient.close();
      executor.close();
      quota.close();
      originGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }
}
