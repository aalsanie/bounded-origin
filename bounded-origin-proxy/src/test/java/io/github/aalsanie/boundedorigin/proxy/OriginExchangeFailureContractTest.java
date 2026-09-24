package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginExchangeFailureContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void responseSpoolCreatedAfterRejectedCancellationIsDisposedBeforeInstallation()
      throws Exception {
    try (ExchangeFixture fixture = fixture(true)) {
      Method cancel = fixture.type.getDeclaredMethod("cancel", Throwable.class);
      cancel.setAccessible(true);
      cancel.invoke(fixture.handler, new InterruptedException("cancel before response"));
      assertThrows(CompletionException.class, fixture.result::join);
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.send(response);
      org.junit.jupiter.api.Assertions.assertEquals(1, fixture.quota.files());
      org.junit.jupiter.api.Assertions.assertEquals(0, fixture.quota.bytes());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abortRacingCompletedResponseDisposesOnlyAnUnpublishedBody(boolean publishedFirst)
      throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.send(response);
      StreamingSpool spool = fixture.responseSpool();
      spool.append(new byte[] {7}).toCompletableFuture().join();
      StreamingSpool.Result completed = spool.finish().toCompletableFuture().join();
      Field finished = fixture.type.getDeclaredField("finished");
      finished.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicBoolean) finished.get(fixture.handler)).set(true);
      if (publishedFirst) {
        fixture.completeResponse(completed, null);
      }
      Method abort = fixture.type.getDeclaredMethod("abortDelivery", Throwable.class);
      abort.setAccessible(true);
      abort.invoke(fixture.handler, new RejectedExecutionException("late task rejection"));
      if (publishedFirst) {
        Artifact artifact = fixture.result.join();
        assertTrue(Files.exists(completed.path()));
        try (var input = artifact.body().openStream()) {
          org.junit.jupiter.api.Assertions.assertEquals(7, input.read());
          org.junit.jupiter.api.Assertions.assertEquals(-1, input.read());
        }
        artifact.body().close();
      } else {
        fixture.completeResponse(completed, null);
        assertThrows(CompletionException.class, fixture.result::join);
      }
      assertTrue(completed.released());
      assertTrue(Files.notExists(completed.path()));
      org.junit.jupiter.api.Assertions.assertEquals(1, fixture.quota.files());
      org.junit.jupiter.api.Assertions.assertEquals(0, fixture.quota.bytes());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"finish", "append", "cancel"})
  void rejectedOriginSpoolHandoffFailsExchangeAndReleasesData(String handoff) throws Exception {
    try (ExchangeFixture fixture = fixture(true)) {
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.send(response);
      if (handoff.equals("append")) {
        ChannelHandlerContext actual =
            fixture.exchangeChannel.pipeline().context((ChannelHandler) fixture.handler);
        EventLoop rejected = rejectingLoop(fixture.leaseChannel.eventLoop());
        ChannelHandlerContext context =
            (ChannelHandlerContext)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ChannelHandlerContext.class},
                    (proxy, method, arguments) ->
                        method.getName().equals("executor")
                            ? rejected
                            : method.invoke(actual, arguments));
        Method contentHandler =
            fixture.type.getDeclaredMethod(
                "handleContent",
                ChannelHandlerContext.class,
                io.netty.handler.codec.http.HttpContent.class);
        contentHandler.setAccessible(true);
        var content = new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1}));
        try {
          contentHandler.invoke(fixture.handler, context, content);
        } finally {
          content.release();
        }
      } else {
        fixture.responseSpool().append(new byte[] {1}).toCompletableFuture().join();
        Method terminal =
            handoff.equals("finish")
                ? fixture.type.getDeclaredMethod("finishResponse")
                : fixture.type.getDeclaredMethod("cancel", Throwable.class);
        terminal.setAccessible(true);
        if (handoff.equals("finish")) {
          terminal.invoke(fixture.handler);
        } else {
          terminal.invoke(fixture.handler, new InterruptedException("caller gone"));
        }
      }
      assertThrows(ExecutionException.class, () -> fixture.result.get(5, TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertEquals(1, fixture.quota.files());
      org.junit.jupiter.api.Assertions.assertEquals(0, fixture.quota.bytes());
      try (var files = Files.newDirectoryStream(temporaryDirectory, "origin-response-*.tmp")) {
        org.junit.jupiter.api.Assertions.assertFalse(files.iterator().hasNext());
      }
    }
  }

  @Test
  void decoderRejectedResponseFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse response = response(200);
      response.setDecoderResult(DecoderResult.failure(new IOException("bad response")));
      fixture.send(response);
      fixture.assertFailed();
    }
  }

  @Test
  void overlappingInformationalResponsesFailExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      fixture.send(response(103));
      fixture.send(response(103));
      fixture.assertFailed();
    }
  }

  @Test
  void finalResponseBeforeInformationalFramingEndsFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      fixture.send(response(103));
      fixture.send(response(200));
      fixture.assertFailed();
    }
  }

  @Test
  void secondFinalResponseFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse first = response(200);
      first.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
      fixture.send(first);

      HttpResponse second = response(200);
      second.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
      fixture.send(second);
      fixture.assertFailed();
    }
  }

  @Test
  void malformedContentLengthFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "not-a-number");
      fixture.send(response);
      fixture.assertFailed();
    }
  }

  @Test
  void decoderRejectedContentFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      DefaultHttpContent content = new DefaultHttpContent(Unpooled.EMPTY_BUFFER);
      content.setDecoderResult(DecoderResult.failure(new IOException("bad content")));
      fixture.send(content);
      fixture.assertFailed();
    }
  }

  @Test
  void informationalResponseBodyFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      fixture.send(response(103));
      fixture.send(new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));
      fixture.assertFailed();
    }
  }

  @Test
  void contentBeforeFinalResponseFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      fixture.send(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));
      fixture.assertFailed();
    }
  }

  @Test
  void bodyForbiddenResponseRejectsContent() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      fixture.send(response(204));
      fixture.send(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));
      fixture.assertFailed();
    }
  }

  @Test
  void completedSpoolFailureFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      fixture.completeResponse(null, new IOException("spool completion failed"));
      fixture.assertFailed();
    }
  }

  @Test
  void contentLengthMismatchClosesCompletedSpoolAndFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "2");
      fixture.send(response);

      StreamingSpool.Result completed = fixture.completedBody(new byte[] {1});
      Path path = completed.path();
      fixture.completeResponse(completed, null);
      fixture.assertFailed();
      assertTrue(completed.released());
      assertTrue(Files.notExists(path));
    }
  }

  @Test
  void artifactConstructionFailureClosesCompletedSpoolAndFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      StreamingSpool.Result completed = fixture.completedBody(new byte[0]);
      Path path = completed.path();

      fixture.completeResponse(completed, null);
      fixture.assertFailed();

      assertTrue(completed.released());
      assertTrue(Files.notExists(path));
    }
  }

  @Test
  void asynchronousOriginSpoolWriteFailureFailsExchange() throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.send(response);

      StreamingSpool spool = fixture.responseSpool();
      fileChannel(spool).close();

      fixture.send(new DefaultLastHttpContent(Unpooled.wrappedBuffer(new byte[] {1})));
      fixture.assertFailed();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void abandonedWaiterDisposesResultWhenCompletionWinsTheCancellationRace(boolean completeFirst)
      throws Exception {
    try (ExchangeFixture fixture = fixture()) {
      HttpResponse response = response(200);
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
      fixture.send(response);
      Field finished = fixture.type.getDeclaredField("finished");
      finished.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicBoolean) finished.get(fixture.handler)).set(true);
      StreamingSpool.Result completed = fixture.completedBody(new byte[] {1});
      Method abandon = fixture.type.getDeclaredMethod("abandon", Throwable.class);
      abandon.setAccessible(true);
      if (completeFirst) {
        fixture.completeResponse(completed, null);
      }
      abandon.invoke(fixture.handler, new InterruptedException("waiter stopped"));
      if (!completeFirst) {
        fixture.completeResponse(completed, null);
      }
      fixture.leaseChannel.runPendingTasks();
      assertTrue(completed.released());
      assertTrue(Files.notExists(completed.path()));
      assertTrue(((TemporaryArtifactBody) fixture.result.join().body()).deleted());
    }
  }

  private ExchangeFixture fixture() throws Exception {
    return fixture(false);
  }

  private ExchangeFixture fixture(boolean rejectDelivery) throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    GatewayMetrics metrics = new GatewayMetrics();
    SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    OriginConnectionPool pool = new OriginConnectionPool(group, config, metrics);
    EmbeddedChannel leaseChannel = new EmbeddedChannel();
    Channel leaseTransport = leaseChannel;
    if (rejectDelivery) {
      EventLoop rejected = rejectingLoop(leaseChannel.eventLoop());
      leaseTransport =
          (Channel)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Channel.class},
                  (proxy, method, arguments) ->
                      method.getName().equals("eventLoop")
                          ? rejected
                          : method.invoke(leaseChannel, arguments));
    }

    Constructor<OriginConnectionPool.Lease> leaseConstructor =
        OriginConnectionPool.Lease.class.getDeclaredConstructor(
            OriginConnectionPool.class, Channel.class);
    leaseConstructor.setAccessible(true);
    OriginConnectionPool.Lease lease = leaseConstructor.newInstance(pool, leaseTransport);

    StreamingSpool.Result requestBody = completedBody(quota, new byte[0], "request-");
    OriginRequest request =
        new OriginRequest("GET", "/", Map.of("host", List.of("example.test")), requestBody, 1024);

    Class<?> type =
        Class.forName(
            "io.github.aalsanie.boundedorigin.proxy.NettyOriginClient$OriginExchangeHandler");
    Constructor<?> constructor =
        type.getDeclaredConstructor(
            OriginConnectionPool.Lease.class,
            OriginRequest.class,
            GatewayConfig.class,
            GatewayMetrics.class,
            SpoolQuota.class);
    constructor.setAccessible(true);
    Object handler = constructor.newInstance(lease, request, config, metrics, quota);

    Method resultMethod = type.getDeclaredMethod("result");
    resultMethod.setAccessible(true);
    @SuppressWarnings("unchecked")
    CompletableFuture<Artifact> result = (CompletableFuture<Artifact>) resultMethod.invoke(handler);

    EmbeddedChannel exchangeChannel = new EmbeddedChannel((ChannelHandler) handler);
    return new ExchangeFixture(
        type,
        handler,
        result,
        exchangeChannel,
        leaseChannel,
        requestBody,
        pool,
        quota,
        group,
        temporaryDirectory);
  }

  private static HttpResponse response(int status) {
    return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
  }

  private static EventLoop rejectingLoop(EventLoop actual) {
    return (EventLoop)
        Proxy.newProxyInstance(
            OriginExchangeFailureContractTest.class.getClassLoader(),
            new Class<?>[] {EventLoop.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("inEventLoop")) {
                return false;
              }
              if (method.getName().equals("execute")) {
                throw new RejectedExecutionException("origin event loop terminated");
              }
              return method.invoke(actual, arguments);
            });
  }

  private static FileChannel fileChannel(StreamingSpool spool) throws Exception {
    Field field = StreamingSpool.class.getDeclaredField("channel");
    field.setAccessible(true);
    return (FileChannel) field.get(spool);
  }

  private static StreamingSpool.Result completedBody(SpoolQuota quota, byte[] bytes, String prefix)
      throws Exception {
    Path path = Files.createTempFile(prefix, ".tmp");
    Files.write(path, bytes);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, bytes.length, "digest", reservation);
  }

  private static final class ExchangeFixture implements AutoCloseable {
    private final Class<?> type;
    private final Object handler;
    private final CompletableFuture<Artifact> result;
    private final EmbeddedChannel exchangeChannel;
    private final EmbeddedChannel leaseChannel;
    private final StreamingSpool.Result requestBody;
    private final OriginConnectionPool pool;
    private final SpoolQuota quota;
    private final EventLoopGroup group;
    private final Path temporaryDirectory;

    private ExchangeFixture(
        Class<?> type,
        Object handler,
        CompletableFuture<Artifact> result,
        EmbeddedChannel exchangeChannel,
        EmbeddedChannel leaseChannel,
        StreamingSpool.Result requestBody,
        OriginConnectionPool pool,
        SpoolQuota quota,
        EventLoopGroup group,
        Path temporaryDirectory) {
      this.type = type;
      this.handler = handler;
      this.result = result;
      this.exchangeChannel = exchangeChannel;
      this.leaseChannel = leaseChannel;
      this.requestBody = requestBody;
      this.pool = pool;
      this.quota = quota;
      this.group = group;
      this.temporaryDirectory = temporaryDirectory;
    }

    private void send(Object message) {
      exchangeChannel.writeInbound(message);
    }

    private void assertFailed() throws Exception {
      pumpUntilDone();
      assertThrows(CompletionException.class, result::join);
    }

    private void completeResponse(StreamingSpool.Result body, Throwable failure) throws Exception {
      Method method =
          type.getDeclaredMethod("completeResponse", StreamingSpool.Result.class, Throwable.class);
      method.setAccessible(true);
      method.invoke(handler, body, failure);
    }

    private StreamingSpool.Result completedBody(byte[] bytes) throws Exception {
      Path path = Files.createTempFile(temporaryDirectory, "origin-completed-", ".tmp");
      Files.write(path, bytes);
      SpoolQuota.Reservation reservation = quota.openFile();
      reservation.reserve(bytes.length);
      return new StreamingSpool.Result(path, bytes.length, "digest", reservation);
    }

    private StreamingSpool responseSpool() throws Exception {
      Field field = type.getDeclaredField("spool");
      field.setAccessible(true);
      StreamingSpool spool = (StreamingSpool) field.get(handler);
      if (spool == null) {
        throw new AssertionError("origin response spool was not created");
      }
      return spool;
    }

    private void pumpUntilDone() throws InterruptedException {
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (!result.isDone() && System.nanoTime() - deadline < 0) {
        exchangeChannel.runPendingTasks();
        exchangeChannel.runScheduledPendingTasks();
        leaseChannel.runPendingTasks();
        leaseChannel.runScheduledPendingTasks();
        Thread.sleep(1);
      }
      assertTrue(result.isDone());
    }

    @Override
    public void close() {
      try {
        Field field = type.getDeclaredField("spool");
        field.setAccessible(true);
        StreamingSpool spool = (StreamingSpool) field.get(handler);
        if (spool != null) {
          spool.close();
        }
      } catch (ReflectiveOperationException ignored) {
      }
      requestBody.close();
      exchangeChannel.finishAndReleaseAll();
      leaseChannel.finishAndReleaseAll();
      pool.close();
      quota.close();
      group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }
}
