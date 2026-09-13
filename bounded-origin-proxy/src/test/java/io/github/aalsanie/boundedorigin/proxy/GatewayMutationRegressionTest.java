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
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayMutationRegressionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void malformedBodyFailuresCountInputAndReleaseAllRequestState() throws Exception {
    try (Fixture fixture = fixture(false, null)) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/decoder-failure");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);
      DefaultLastHttpContent content =
          new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1}));
      content.setDecoderResult(DecoderResult.failure(new IOException("bad body")));
      fixture.channel().writeInbound(content);
      assertStatus(fixture.channel(), 400);
      assertTerminal(fixture, 1, 0);
    }

    try (Fixture fixture = fixture(false, null)) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/too-long");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);
      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1, 2})));
      assertStatus(fixture.channel(), 400);
      assertTerminal(fixture, 1, 0);
    }

    try (Fixture fixture = fixture(false, null)) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/too-short");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "2");
      fixture.channel().writeInbound(request);
      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));
      assertStatus(fixture.channel(), 400);
      assertTerminal(fixture, 1, 0);
    }
  }

  @Test
  void overlappingRequestTerminatesAndReleasesTheFirstRequest() throws Exception {
    try (Fixture fixture = fixture(false, null)) {
      DefaultHttpRequest first = request(HttpMethod.POST, "/one");
      first.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(first);
      assertEquals(1, fixture.runtime().activeRequests());
      assertEquals(1, fixture.quota().files());

      fixture.channel().writeInbound(request(HttpMethod.GET, "/two"));
      fixture.channel().runPendingTasks();

      assertFalse(fixture.channel().isActive());
      assertEquals(1, fixture.metrics().snapshot().malformedRequests());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());
    }
  }

  @Test
  void drainingRequestCountsRejectionWithoutCreatingRequestState() throws Exception {
    try (Fixture fixture = fixture(false, null)) {
      fixture.runtime().beginDrain();
      fixture.channel().writeInbound(request(HttpMethod.GET, "/draining"));
      assertStatus(fixture.channel(), 503);
      fixture.channel().runPendingTasks();

      assertEquals(1, fixture.metrics().snapshot().rejections());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(0, fixture.quota().files());
    }
  }

  @Test
  void requestTimeoutBeforeResponseStartsUsesVirtualTimeAndReleasesSpool() throws Exception {
    try (Fixture fixture = fixture(false, null)) {
      fixture.channel().freezeTime();
      DefaultHttpRequest request = request(HttpMethod.POST, "/timeout");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);
      assertEquals(1, fixture.runtime().activeRequests());
      assertEquals(1, fixture.quota().files());

      advancePastRequestTimeout(fixture.channel());
      assertStatus(fixture.channel(), 408);
      fixture.channel().runPendingTasks();

      assertEquals(1, fixture.metrics().snapshot().rejections());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());
      assertFalse(fixture.channel().isActive());
    }
  }

  @Test
  void timeoutAfterResponseStartsTerminatesExactlyOnce() throws Exception {
    try (Fixture fixture = fixture(false, null)) {
      fixture.channel().freezeTime();
      DefaultHttpRequest request = request(HttpMethod.POST, "/response-started-timeout");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);
      assertEquals(1, fixture.runtime().activeRequests());
      assertEquals(1, fixture.quota().files());
      markResponseStarted(fixture.channel());

      advancePastRequestTimeout(fixture.channel());
      awaitInactive(fixture.channel());

      assertEquals(1, fixture.metrics().snapshot().rejections());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());

      advancePastRequestTimeout(fixture.channel());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(1, fixture.metrics().snapshot().rejections());
    }
  }

  @Test
  void successfulContinueCompletionAfterTimeoutCannotReviveRequest() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.DEFER_CONTINUE);
    try (Fixture fixture = fixture(false, outbound)) {
      fixture.channel().freezeTime();
      DefaultHttpRequest request = request(HttpMethod.POST, "/continue-timeout");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
      fixture.channel().writeInbound(request);
      assertTrue(outbound.awaitIntercept(fixture.channel()));

      advancePastRequestTimeout(fixture.channel());
      assertStatus(fixture.channel(), 408);
      awaitNoActiveRequests(fixture);
      assertEquals(1, fixture.metrics().snapshot().rejections());
      assertEquals(0, fixture.quota().files());

      outbound.succeedDeferred();
      fixture.channel().runPendingTasks();
      awaitInactive(fixture.channel());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(1, fixture.metrics().snapshot().rejections());
    }
  }

  @Test
  void failedContinueWriteTerminatesAndReleasesSpool() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.FAIL_CONTINUE);
    try (Fixture fixture = fixture(false, outbound)) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/continue");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
      fixture.channel().writeInbound(request);
      assertTrue(outbound.awaitIntercept(fixture.channel()));
      fixture.channel().runPendingTasks();

      assertFalse(fixture.channel().isActive());
      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());
    }
  }

  @Test
  void failedFinalWriteTerminatesAndReleasesRuntimeState() throws Exception {
    ControlledOutbound outbound = new ControlledOutbound(Mode.FAIL_FINAL);
    try (Fixture fixture = fixture(true, outbound)) {
      completeGet(fixture.channel(), "/failed-final");
      assertTrue(outbound.awaitIntercept(fixture.channel()));
      awaitInactive(fixture.channel());

      assertEquals(0, fixture.runtime().activeRequests());
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());
      assertEquals(0, fixture.metrics().snapshot().rejections());
    }
  }

  @Test
  void successfulKeepAliveResponseRemainsUsablePastOriginalTimeout() throws Exception {
    try (Fixture fixture = fixture(true, null)) {
      fixture.channel().freezeTime();
      completeGet(fixture.channel(), "/one");
      assertStatus(fixture.channel(), 200);
      awaitNoActiveRequests(fixture);

      advancePastRequestTimeout(fixture.channel());
      assertTrue(fixture.channel().isActive());
      assertEquals(0, fixture.metrics().snapshot().rejections());

      completeGet(fixture.channel(), "/two");
      assertStatus(fixture.channel(), 200);
      awaitNoActiveRequests(fixture);
      assertTrue(fixture.channel().isActive());
      assertEquals(2, fixture.metrics().snapshot().requests());
      assertEquals(2, fixture.metrics().snapshot().artifactHits());
    }
  }

  private Fixture fixture(boolean storedArtifact, ControlledOutbound outbound) throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(
            GatewayTestFixtures.unusedPort(),
            temporaryDirectory,
            Map.of(
                "origin.max-execution-duration", "PT2S",
                "origin.response-timeout", "PT2S",
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
    EmbeddedChannel channel =
        outbound == null ? new EmbeddedChannel(handler) : new EmbeddedChannel(outbound, handler);
    return new Fixture(
        channel, outbound, runtime, metrics, quota, executor, originClient, originGroup);
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

  private static void advancePastRequestTimeout(EmbeddedChannel channel) {
    channel.advanceTimeBy(4, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    channel.runPendingTasks();
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

  private static void assertTerminal(Fixture fixture, long malformed, long rejections)
      throws InterruptedException {
    awaitNoActiveRequests(fixture);
    assertEquals(malformed, fixture.metrics().snapshot().malformedRequests());
    assertEquals(rejections, fixture.metrics().snapshot().rejections());
    assertEquals(0, fixture.quota().files());
    assertEquals(0, fixture.quota().bytes());
  }

  private static void awaitNoActiveRequests(Fixture fixture) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (fixture.runtime().activeRequests() != 0 && System.nanoTime() - deadline < 0) {
      fixture.channel().runPendingTasks();
      fixture.channel().runScheduledPendingTasks();
      Thread.sleep(1);
    }
    assertEquals(0, fixture.runtime().activeRequests());
  }

  private static void awaitInactive(EmbeddedChannel channel) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (channel.isActive() && System.nanoTime() - deadline < 0) {
      channel.runPendingTasks();
      channel.runScheduledPendingTasks();
      Thread.sleep(1);
    }
    assertFalse(channel.isActive());
  }

  private static void markResponseStarted(EmbeddedChannel channel) throws Exception {
    GatewayRequestHandler handler = channel.pipeline().get(GatewayRequestHandler.class);
    Field currentField = GatewayRequestHandler.class.getDeclaredField("current");
    currentField.setAccessible(true);
    Object state = currentField.get(handler);
    if (state == null) {
      throw new AssertionError("request state was not created");
    }
    Field responseStartedField = state.getClass().getDeclaredField("responseStarted");
    responseStartedField.setAccessible(true);
    responseStartedField.setBoolean(state, true);
  }

  private enum Mode {
    FAIL_CONTINUE,
    DEFER_CONTINUE,
    FAIL_FINAL,
    DEFER_FINAL
  }

  private static final class ControlledOutbound extends ChannelOutboundHandlerAdapter {
    private final Mode mode;
    private ChannelPromise deferred;
    private boolean intercepted;

    private ControlledOutbound(Mode mode) {
      this.mode = mode;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      if (message instanceof HttpResponse response && shouldIntercept(response.status().code())) {
        ReferenceCountUtil.release(message);
        intercepted = true;
        if (mode == Mode.DEFER_CONTINUE || mode == Mode.DEFER_FINAL) {
          deferred = promise;
        } else {
          promise.setFailure(new IOException("controlled downstream failure"));
        }
        return;
      }
      context.write(message, promise);
    }

    private boolean shouldIntercept(int status) {
      return switch (mode) {
        case FAIL_CONTINUE, DEFER_CONTINUE -> status == 100;
        case FAIL_FINAL, DEFER_FINAL -> status != 100;
      };
    }

    private boolean awaitIntercept(EmbeddedChannel channel) throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (System.nanoTime() - deadline < 0) {
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
        if (intercepted) {
          return true;
        }
        Thread.sleep(1);
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
      GatewayRuntimeState runtime,
      GatewayMetrics metrics,
      SpoolQuota quota,
      BoundedOriginExecutor executor,
      NettyOriginClient originClient,
      EventLoopGroup originGroup)
      implements AutoCloseable {
    @Override
    public void close() {
      if (outbound != null) {
        outbound.failDeferred();
      }
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
