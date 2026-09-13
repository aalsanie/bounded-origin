package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayHandlerLifecycleTest {
  @TempDir Path temporaryDirectory;

  @Test
  void idleConnectionWithoutRequestIsClosed() throws Exception {
    try (Fixture fixture = fixture()) {
      EmbeddedChannel channel = fixture.channel();
      assertTrue(channel.isActive());
      channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
      channel.runPendingTasks();
      assertFalse(channel.isActive());
    }
  }

  @Test
  void idleEventDoesNotCloseConnectionWithActiveRequest() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/active");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);

      fixture
          .channel()
          .pipeline()
          .fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
      fixture.channel().runPendingTasks();

      assertTrue(fixture.channel().isActive());
      assertEquals(1, fixture.runtime().activeRequests());
    }
  }

  @Test
  void nonIdleEventsContinueThroughPipeline() throws Exception {
    AtomicBoolean observed = new AtomicBoolean();
    try (Fixture fixture =
        fixture(
            new ChannelInboundHandlerAdapter() {
              @Override
              public void userEventTriggered(
                  io.netty.channel.ChannelHandlerContext context, Object event) {
                observed.set(true);
              }
            })) {
      fixture.channel().pipeline().fireUserEventTriggered(new Object());
      assertTrue(observed.get());
    }
  }

  @Test
  void clientExceptionWithoutActiveRequestClosesConnection() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.channel().pipeline().fireExceptionCaught(new IOException("client failed"));
      fixture.channel().runPendingTasks();
      assertFalse(fixture.channel().isActive());
    }
  }

  @Test
  void clientExceptionWithActiveRequestTerminatesRequestAndClosesConnection() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/active");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);
      assertEquals(1, fixture.runtime().activeRequests());

      fixture.channel().pipeline().fireExceptionCaught(new IOException("client failed"));
      fixture.channel().runPendingTasks();

      assertFalse(fixture.channel().isActive());
      assertEquals(0, fixture.runtime().activeRequests());
    }
  }

  @Test
  void contentWithoutRequestClosesConnection() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.channel().writeInbound(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
      fixture.channel().runPendingTasks();
      assertFalse(fixture.channel().isActive());
    }
  }

  @Test
  void decoderRejectedRequestContentReturnsBadRequest() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/bad-body");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);

      DefaultLastHttpContent content =
          new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1}));
      content.setDecoderResult(DecoderResult.failure(new IOException("bad content")));
      fixture.channel().writeInbound(content);
      assertStatus(fixture, 400);
    }
  }

  @Test
  void requestBodyLongerThanDeclaredLengthReturnsBadRequest() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/too-long");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);
      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1, 2})));

      assertStatus(fixture, 400);
    }
  }

  @Test
  void requestBodyShorterThanDeclaredLengthReturnsBadRequest() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/too-short");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "2");
      fixture.channel().writeInbound(request);
      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));

      assertStatus(fixture, 400);
    }
  }

  @Test
  void drainingRuntimeRejectsNewRequestWithServiceUnavailable() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.runtime().beginDrain();
      fixture.channel().writeInbound(request(HttpMethod.GET, "/draining"));

      assertStatus(fixture, 503);
    }
  }

  @Test
  void connectRequestReturnsMethodNotAllowed() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.channel().writeInbound(request(HttpMethod.CONNECT, "/tunnel"));

      assertStatus(fixture, 405);
    }
  }

  @Test
  void expectContinueWritesInterimResponseAndKeepsRequestOpen() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/continue");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      request.headers().set(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE);
      fixture.channel().writeInbound(request);

      assertStatus(fixture, 100);
      assertTrue(fixture.channel().isActive());
      assertEquals(1, fixture.runtime().activeRequests());
    }
  }

  @Test
  void secondRequestBeforeFirstBodyCompletesClosesConnection() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest first = request(HttpMethod.POST, "/one");
      first.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(first);
      fixture.channel().writeInbound(request(HttpMethod.GET, "/two"));
      fixture.channel().runPendingTasks();
      assertFalse(fixture.channel().isActive());
    }
  }

  private Fixture fixture(ChannelInboundHandlerAdapter... trailing) throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    GatewayMetrics metrics = new GatewayMetrics();
    GatewayRuntimeState runtime = new GatewayRuntimeState(config.maxClientConnections());
    runtime.started();
    SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    EventLoopGroup originGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            config.globalBudget(), config.failureCooldown(), config.maxCooldownEntries());
    NettyOriginClient originClient = new NettyOriginClient(originGroup, config, metrics, quota);
    FlightLeaseRegistry flights = new FlightLeaseRegistry(metrics);
    GatewayRequestProcessor processor =
        new GatewayRequestProcessor(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore(),
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
    ResponseCapture responses = new ResponseCapture();
    io.netty.channel.ChannelHandler[] handlers =
        new io.netty.channel.ChannelHandler[trailing.length + 2];
    handlers[0] = responses;
    handlers[1] = handler;
    System.arraycopy(trailing, 0, handlers, 2, trailing.length);

    EmbeddedChannel channel = new EmbeddedChannel(handlers);
    return new Fixture(channel, responses, runtime, quota, executor, originClient, originGroup);
  }

  private static DefaultHttpRequest request(HttpMethod method, String target) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, target);
    request.headers().set(HttpHeaderNames.HOST, "example.test");
    return request;
  }

  private static void assertStatus(Fixture fixture, int expected) throws Exception {
    int actual = fixture.responses().awaitStatus(fixture.channel());
    assertEquals(expected, actual);
  }

  private static final class ResponseCapture extends ChannelOutboundHandlerAdapter {
    private final Queue<Integer> completedStatuses = new ConcurrentLinkedQueue<>();
    private volatile int currentStatus = -1;

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      if (message instanceof HttpResponse response) {
        currentStatus = response.status().code();
      }
      int status = currentStatus;
      if (message instanceof LastHttpContent && status >= 0) {
        promise.addListener(
            future -> {
              if (future.isSuccess()) {
                if (!completedStatuses.offer(status)) {
                  throw new IllegalStateException("response status capture rejected");
                }
              }
            });
      }
      context.write(message, promise);
    }

    private int awaitStatus(EmbeddedChannel channel) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() - deadline < 0) {
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();
        Integer status = completedStatuses.poll();
        if (status != null) {
          return status;
        }
        Thread.sleep(1);
      }
      throw new AssertionError("response was not completed");
    }
  }

  private record Fixture(
      EmbeddedChannel channel,
      ResponseCapture responses,
      GatewayRuntimeState runtime,
      SpoolQuota quota,
      BoundedOriginExecutor executor,
      NettyOriginClient originClient,
      EventLoopGroup originGroup)
      implements AutoCloseable {
    @Override
    public void close() {
      channel.finishAndReleaseAll();
      runtime.closed();
      originClient.close();
      executor.close();
      quota.close();
      originGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }
}
