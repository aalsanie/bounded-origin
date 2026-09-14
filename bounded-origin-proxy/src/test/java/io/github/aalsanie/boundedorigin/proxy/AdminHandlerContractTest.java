package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminHandlerContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void runningEndpointsAndUnknownRouteReturnExpectedStatuses() throws Exception {
    assertStatus(request(HttpMethod.GET, "/health"), 200);
    assertStatus(request(HttpMethod.GET, "/ready"), 200);
    assertStatus(request(HttpMethod.GET, "/metrics"), 200);
    assertStatus(request(HttpMethod.GET, "/missing"), 404);
  }

  @Test
  void adminRejectsUnsupportedMethodAndInvalidAuthorityOrBodyFraming() throws Exception {
    assertStatus(request(HttpMethod.POST, "/health"), 405);

    DefaultHttpRequest missingHost = request(HttpMethod.GET, "/health");
    missingHost.headers().remove(HttpHeaderNames.HOST);
    assertStatus(missingHost, 400);

    DefaultHttpRequest duplicateHost = request(HttpMethod.GET, "/health");
    duplicateHost.headers().add(HttpHeaderNames.HOST, "other");
    assertStatus(duplicateHost, 400);

    DefaultHttpRequest nonZeroLength = request(HttpMethod.GET, "/health");
    nonZeroLength.headers().set(HttpHeaderNames.CONTENT_LENGTH, "1");
    assertStatus(nonZeroLength, 400);

    DefaultHttpRequest malformedLength = request(HttpMethod.GET, "/health");
    malformedLength.headers().set(HttpHeaderNames.CONTENT_LENGTH, "not-a-number");
    assertStatus(malformedLength, 400);
  }

  @Test
  void decoderFailureReturnsBadRequest() throws Exception {
    DefaultHttpRequest request = request(HttpMethod.GET, "/health");
    request.setDecoderResult(DecoderResult.failure(new IOException("decoder failure")));
    assertStatus(request, 400);
  }

  @Test
  void nonRequestMessagesAreConsumedAndReleased() throws Exception {
    try (Fixture fixture = fixture()) {
      ByteBuf buffer = Unpooled.buffer(1).writeByte(1);
      assertFalse(fixture.channel().writeInbound(buffer));
      assertEquals(0, buffer.refCnt());
    }
  }

  @Test
  void exceptionClosesAdminConnection() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.channel().pipeline().fireExceptionCaught(new IOException("boom"));
      fixture.channel().runPendingTasks();
      assertFalse(fixture.channel().isActive());
    }
  }

  private void assertStatus(DefaultHttpRequest request, int expected) throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.channel().writeInbound(request);
      awaitOutbound(fixture.channel());
      Object outbound = fixture.channel().readOutbound();
      try {
        assertTrue(outbound instanceof HttpResponse);
        assertEquals(expected, ((HttpResponse) outbound).status().code());
      } finally {
        ReferenceCountUtil.release(outbound);
      }
    }
  }

  private Fixture fixture() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    GatewayRuntimeState runtime = new GatewayRuntimeState(config.maxClientConnections());
    runtime.started();
    GatewayMetrics metrics = new GatewayMetrics();
    SpoolQuota quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    EventLoopGroup originGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    BoundedOriginExecutor executor =
        new BoundedOriginExecutor(
            config.globalBudget(), config.failureCooldown(), config.maxCooldownEntries());
    NettyOriginClient originClient = new NettyOriginClient(originGroup, config, metrics, quota);
    EmbeddedChannel channel =
        new EmbeddedChannel(new AdminHandler(runtime, metrics, executor, originClient, quota));
    return new Fixture(channel, runtime, quota, executor, originClient, originGroup);
  }

  private static DefaultHttpRequest request(HttpMethod method, String target) {
    DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, target);
    request.headers().set(HttpHeaderNames.HOST, "admin");
    return request;
  }

  private static void awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      channel.runPendingTasks();
      if (!channel.outboundMessages().isEmpty()) {
        return;
      }
      Thread.sleep(1);
    }
    throw new AssertionError("admin response was not written");
  }

  private record Fixture(
      EmbeddedChannel channel,
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
