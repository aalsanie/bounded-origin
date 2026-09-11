package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.OriginExecutorMetrics;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class AdminHandler extends ChannelInboundHandlerAdapter {
  private final GatewayRuntimeState runtime;
  private final GatewayMetrics metrics;
  private final BoundedOriginExecutor executor;
  private final NettyOriginClient originClient;
  private final SpoolQuota spoolQuota;

  AdminHandler(
      GatewayRuntimeState runtime,
      GatewayMetrics metrics,
      BoundedOriginExecutor executor,
      NettyOriginClient originClient,
      SpoolQuota spoolQuota) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.originClient = Objects.requireNonNull(originClient, "originClient");
    this.spoolQuota = Objects.requireNonNull(spoolQuota, "spoolQuota");
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object message) {
    try {
      if (!(message instanceof HttpRequest request)) {
        return;
      }
      if (!request.decoderResult().isSuccess()) {
        respond(
            context, HttpResponseStatus.BAD_REQUEST, "text/plain; charset=utf-8", "bad request\n");
        return;
      }
      if (!HttpMethod.GET.equals(request.method())) {
        respond(
            context,
            HttpResponseStatus.METHOD_NOT_ALLOWED,
            "text/plain; charset=utf-8",
            "method not allowed\n");
        return;
      }
      if (request.headers().getAll(HttpHeaderNames.HOST).size() != 1
          || request.headers().contains(HttpHeaderNames.TRANSFER_ENCODING)
          || HttpUtil.getContentLength(request, 0) != 0) {
        respond(
            context, HttpResponseStatus.BAD_REQUEST, "text/plain; charset=utf-8", "bad request\n");
        return;
      }

      switch (request.uri()) {
        case "/health" ->
            respond(
                context,
                runtime.healthy() ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE,
                "text/plain; charset=utf-8",
                runtime.healthy() ? "ok\n" : "unhealthy\n");
        case "/ready" ->
            respond(
                context,
                runtime.ready() ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE,
                "text/plain; charset=utf-8",
                runtime.ready() ? "ready\n" : "draining\n");
        case "/metrics" ->
            respond(
                context,
                HttpResponseStatus.OK,
                "text/plain; version=0.0.4; charset=utf-8",
                metrics.prometheus(
                    OriginExecutorMetrics.snapshot(executor),
                    runtime.clientConnections(),
                    runtime.activeRequests(),
                    originClient.openConnections(),
                    originClient.pendingAcquires(),
                    spoolQuota.bytes(),
                    spoolQuota.files()));
        default ->
            respond(
                context, HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8", "not found\n");
      }
    } catch (RuntimeException exception) {
      respond(
          context,
          HttpResponseStatus.INTERNAL_SERVER_ERROR,
          "text/plain; charset=utf-8",
          "admin endpoint failed\n");
    } finally {
      ReferenceCountUtil.release(message);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
    context.close();
  }

  private static void respond(
      ChannelHandlerContext context, HttpResponseStatus status, String contentType, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    DefaultFullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
    response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
    response.headers().set(HttpHeaderNames.CONNECTION, "close");
    context.writeAndFlush(response).addListener(ignored -> context.close());
  }
}
