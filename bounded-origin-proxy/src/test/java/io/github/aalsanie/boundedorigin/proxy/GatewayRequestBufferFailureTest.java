package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayRequestBufferFailureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void requestSpoolCreationFailureReturnsInternalServerErrorAndReleasesRuntime() throws Exception {
    Path file = temporaryDirectory.resolve("not-a-directory");
    Files.writeString(file, "x");

    try (Fixture fixture = fixture(file, Map.of())) {
      fixture.channel().writeInbound(request(HttpMethod.GET, "/spool-create-failure"));

      assertEquals(500, fixture.responses().awaitStatus(fixture.channel()));
      awaitNoActiveRequests(fixture);
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());
    }
  }

  @Test
  void closedRequestSpoolReturnsInternalServerErrorAndRestoresPendingWriteAccounting()
      throws Exception {
    try (Fixture fixture = fixture(temporaryDirectory, Map.of())) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/closed-spool");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);

      Object state = currentState(fixture.handler());
      requestSpool(state).close();

      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));

      assertEquals(500, fixture.responses().awaitStatus(fixture.channel()));
      awaitNoActiveRequests(fixture);
      assertEquals(0, pendingSpoolWrites(state));
    }
  }

  @Test
  void asynchronousRequestSpoolWriteFailureReturnsInternalServerError() throws Exception {
    try (Fixture fixture = fixture(temporaryDirectory, Map.of())) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/failed-write");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.channel().writeInbound(request);

      Object state = currentState(fixture.handler());
      StreamingSpool spool = requestSpool(state);
      fileChannel(spool).close();

      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));

      assertEquals(500, fixture.responses().awaitStatus(fixture.channel()));
      awaitNoActiveRequests(fixture);
      assertEquals(0, pendingSpoolWrites(state));
      assertEquals(0, fixture.quota().files());
      assertEquals(0, fixture.quota().bytes());
    }
  }

  @Test
  void chunkedBodyLimitFailureRestoresPendingWriteAccounting() throws Exception {
    try (Fixture fixture =
        fixture(
            temporaryDirectory,
            Map.of(
                "http.max-request-body-bytes",
                "1",
                "origin.max-result-bytes",
                "8",
                "spool.max-bytes",
                "8"))) {
      DefaultHttpRequest request = request(HttpMethod.POST, "/body-limit");
      request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      fixture.channel().writeInbound(request);

      Object state = currentState(fixture.handler());
      fixture
          .channel()
          .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1, 2})));

      assertEquals(413, fixture.responses().awaitStatus(fixture.channel()));
      awaitNoActiveRequests(fixture);
      assertEquals(0, pendingSpoolWrites(state));
    }
  }

  @Test
  void globalSpoolCapacityFailureIsRetryableAndRestoresPendingWriteAccounting() throws Exception {
    try (Fixture fixture =
        fixture(
            temporaryDirectory,
            Map.of(
                "http.max-request-body-bytes",
                "8",
                "origin.max-result-bytes",
                "8",
                "spool.max-bytes",
                "8"))) {
      SpoolQuota.Reservation blocker = fixture.quota().openFile();
      try {
        blocker.reserve(8);

        DefaultHttpRequest request = request(HttpMethod.POST, "/spool-capacity");
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
        fixture.channel().writeInbound(request);

        Object state = currentState(fixture.handler());
        fixture
            .channel()
            .writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));

        assertEquals(503, fixture.responses().awaitStatus(fixture.channel()));
        awaitNoActiveRequests(fixture);
        assertEquals(0, pendingSpoolWrites(state));
        assertEquals(1, fixture.metrics().snapshot().rejections());
      } finally {
        blocker.close();
      }
    }
  }

  private Fixture fixture(Path spoolDirectory, Map<String, String> overrides) throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), spoolDirectory, overrides);
    GatewayMetrics metrics = new GatewayMetrics();
    GatewayRuntimeState runtime = new GatewayRuntimeState(config.maxClientConnections());
    runtime.started();
    SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    EventLoopGroup originGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            config.globalBudget(), config.failureCooldown(), config.maxCooldownEntries());
    NettyOriginClient originClient = new NettyOriginClient(originGroup, config, metrics, quota);
    GatewayRequestProcessor processor =
        new GatewayRequestProcessor(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
            new GatewayTestFixtures.MemoryArtifactStore(),
            executor,
            originClient,
            metrics,
            new FlightLeaseRegistry(metrics));
    GatewayRequestHandler handler =
        new GatewayRequestHandler(
            config,
            processor,
            new ArtifactResponseWriter(config, metrics),
            metrics,
            runtime,
            quota);
    ResponseCapture responses = new ResponseCapture();
    EmbeddedChannel channel = new EmbeddedChannel(responses, handler);
    return new Fixture(
        channel, handler, responses, runtime, metrics, quota, executor, originClient, originGroup);
  }

  private static DefaultHttpRequest request(HttpMethod method, String path) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path);
    request.headers().set(HttpHeaderNames.HOST, "example.test");
    return request;
  }

  private static Object currentState(GatewayRequestHandler handler) throws Exception {
    Field field = GatewayRequestHandler.class.getDeclaredField("current");
    field.setAccessible(true);
    Object state = field.get(handler);
    if (state == null) {
      throw new AssertionError("request state was not created");
    }
    return state;
  }

  private static StreamingSpool requestSpool(Object state) throws Exception {
    Field field = state.getClass().getDeclaredField("spool");
    field.setAccessible(true);
    return (StreamingSpool) field.get(state);
  }

  private static int pendingSpoolWrites(Object state) throws Exception {
    Field field = state.getClass().getDeclaredField("pendingSpoolWrites");
    field.setAccessible(true);
    return field.getInt(state);
  }

  private static FileChannel fileChannel(StreamingSpool spool) throws Exception {
    Field field = StreamingSpool.class.getDeclaredField("channel");
    field.setAccessible(true);
    return (FileChannel) field.get(spool);
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

  private static final class ResponseCapture extends ChannelOutboundHandlerAdapter {
    private final Queue<Integer> completedStatuses = new ConcurrentLinkedQueue<>();
    private volatile int currentStatus = -1;

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      if (message instanceof HttpResponse response) {
        currentStatus = response.status().code();
      }
      if (message instanceof LastHttpContent) {
        int status = currentStatus;
        promise.addListener(
            future -> {
              if (future.isSuccess() && status >= 0 && !completedStatuses.offer(status)) {
                throw new IllegalStateException("response status capture rejected");
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
      GatewayRequestHandler handler,
      ResponseCapture responses,
      GatewayRuntimeState runtime,
      GatewayMetrics metrics,
      SpoolQuota quota,
      BoundedOriginExecutor executor,
      NettyOriginClient originClient,
      EventLoopGroup originGroup)
      implements AutoCloseable {
    @Override
    public void close() {
      channel.finishAndReleaseAll();
      executor.close();
      originClient.close();
      quota.close();
      originGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }
}
