package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminStateAndFramingTest {
  @TempDir Path temporaryDirectory;

  @Test
  void healthReportsServiceUnavailableAfterRuntimeCloses() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.runtime().closed();
      assertStatus(fixture, request("/health"), 503);
    }
  }

  @Test
  void readinessReportsServiceUnavailableWhileDraining() throws Exception {
    try (Fixture fixture = fixture()) {
      fixture.runtime().beginDrain();
      assertStatus(fixture, request("/ready"), 503);
    }
  }

  @Test
  void transferEncodingIsRejectedOnAdminEndpoint() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request("/health");
      request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
      assertStatus(fixture, request, 400);
    }
  }

  @Test
  void duplicateContentLengthIsRejectedOnAdminEndpoint() throws Exception {
    try (Fixture fixture = fixture()) {
      DefaultHttpRequest request = request("/health");
      request.headers().add(HttpHeaderNames.CONTENT_LENGTH, "0");
      request.headers().add(HttpHeaderNames.CONTENT_LENGTH, "0");
      assertStatus(fixture, request, 400);
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

  private static DefaultHttpRequest request(String target) {
    DefaultHttpRequest request =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, target);
    request.headers().set(HttpHeaderNames.HOST, "admin");
    return request;
  }

  private static void assertStatus(Fixture fixture, DefaultHttpRequest request, int expected)
      throws InterruptedException {
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

  private static void awaitOutbound(EmbeddedChannel channel) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      channel.runPendingTasks();
      if (channel.outboundMessages().size() != 0) {
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
