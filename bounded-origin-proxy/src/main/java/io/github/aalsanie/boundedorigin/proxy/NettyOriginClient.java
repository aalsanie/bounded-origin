package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class NettyOriginClient implements AutoCloseable {
  private final OriginConnectionPool pool;
  private final GatewayConfig config;
  private final GatewayMetrics metrics;
  private final SpoolQuota spoolQuota;

  NettyOriginClient(
      EventLoopGroup group, GatewayConfig config, GatewayMetrics metrics, SpoolQuota spoolQuota) {
    this.config = Objects.requireNonNull(config, "config");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.spoolQuota = Objects.requireNonNull(spoolQuota, "spoolQuota");
    this.pool = new OriginConnectionPool(group, config, metrics);
  }

  Artifact execute(OriginRequest request) throws MaterializationException {
    Objects.requireNonNull(request, "request");
    long startedNanos = System.nanoTime();
    CompletableFuture<OriginConnectionPool.Lease> acquisition =
        pool.acquire().toCompletableFuture();
    OriginConnectionPool.Lease lease;
    try {
      lease = acquisition.get(config.originAcquireTimeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      acquisition.cancel(false);
      Thread.currentThread().interrupt();
      throw new MaterializationException(
          "interrupted while acquiring an origin connection", exception);
    } catch (TimeoutException exception) {
      acquisition.cancel(false);
      metrics.originPoolRejection();
      throw new MaterializationException("origin connection acquisition timed out", exception);
    } catch (ExecutionException exception) {
      throw new MaterializationException("origin connection acquisition failed", unwrap(exception));
    }

    OriginExchangeHandler exchange =
        new OriginExchangeHandler(lease, request, config, metrics, spoolQuota);
    Channel channel = lease.channel();
    channel.pipeline().addLast(exchange);
    try {
      sendRequest(channel, request);
      channel.read();
      return await(exchange);
    } catch (InterruptedException exception) {
      exchange.cancel(exception);
      Thread.currentThread().interrupt();
      throw new MaterializationException("origin request was interrupted", exception);
    } catch (IOException exception) {
      exchange.cancel(exception);
      throw new MaterializationException("failed to stream request to origin", exception);
    } catch (RuntimeException exception) {
      exchange.cancel(exception);
      throw new MaterializationException("origin exchange failed", exception);
    } finally {
      metrics.originDuration(System.nanoTime() - startedNanos);
    }
  }

  int openConnections() {
    return pool.openConnections();
  }

  int pendingAcquires() {
    return pool.pendingAcquires();
  }

  @Override
  public void close() {
    pool.close();
  }

  private Artifact await(OriginExchangeHandler exchange)
      throws MaterializationException, InterruptedException {
    try {
      return exchange.result().get();
    } catch (ExecutionException exception) {
      Throwable cause = unwrap(exception);
      if (cause instanceof StreamingSpool.BodyLimitExceededException) {
        throw new MaterializationException("origin response exceeds the configured limit", cause);
      }
      if (cause instanceof TimeoutException) {
        throw new MaterializationException("origin response timed out", cause);
      }
      throw new MaterializationException("origin response failed", cause);
    }
  }

  private void sendRequest(Channel channel, OriginRequest request)
      throws IOException, InterruptedException {
    HttpRequest outbound =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.valueOf(request.method()), request.target());
    for (Map.Entry<String, java.util.List<String>> header : request.headers().entrySet()) {
      outbound.headers().set(header.getKey(), header.getValue());
    }
    outbound.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
    outbound.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.bodyLength());
    HttpUtil.setKeepAlive(outbound, true);
    sync(channel.writeAndFlush(outbound));

    if (request.bodyLength() != 0) {
      byte[] buffer = new byte[config.maxChunkSize()];
      try (InputStream input = request.body().openStream()) {
        long sent = 0;
        while (sent < request.bodyLength()) {
          int maximum = (int) Math.min(buffer.length, request.bodyLength() - sent);
          int count = input.read(buffer, 0, maximum);
          if (count < 0) {
            throw new IOException("spooled request body ended before its declared length");
          }
          sent += count;
          byte[] chunk = count == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, count);
          sync(channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(chunk))));
        }
        if (input.read() >= 0) {
          throw new IOException("spooled request body exceeds its declared length");
        }
      }
    }
    sync(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT));
  }

  private static void sync(ChannelFuture future) throws IOException, InterruptedException {
    future.sync();
    if (!future.isSuccess()) {
      Throwable cause = future.cause();
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      throw new IOException("origin channel write failed", cause);
    }
  }

  private static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while ((current instanceof ExecutionException
            || current instanceof java.util.concurrent.CompletionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static final class OriginExchangeHandler extends ChannelInboundHandlerAdapter {
    private final OriginConnectionPool.Lease lease;
    private final OriginRequest request;
    private final GatewayConfig config;
    private final GatewayMetrics metrics;
    private final SpoolQuota spoolQuota;
    private final CompletableFuture<Artifact> result = new CompletableFuture<>();
    private final AtomicBoolean finished = new AtomicBoolean();

    private StreamingSpool spool;
    private int statusCode;
    private Map<String, String> metadata = Map.of();
    private boolean reusable = true;
    private long declaredLength = -1;
    private boolean bodyForbidden;
    private ScheduledFuture<?> timeout;

    private OriginExchangeHandler(
        OriginConnectionPool.Lease lease,
        OriginRequest request,
        GatewayConfig config,
        GatewayMetrics metrics,
        SpoolQuota spoolQuota) {
      this.lease = lease;
      this.request = request;
      this.config = config;
      this.metrics = metrics;
      this.spoolQuota = spoolQuota;
      Duration responseTimeout = config.originResponseTimeout();
      this.timeout =
          lease
              .channel()
              .eventLoop()
              .schedule(
                  () -> fail(new TimeoutException("origin response timed out")),
                  responseTimeout.toNanos(),
                  TimeUnit.NANOSECONDS);
    }

    CompletableFuture<Artifact> result() {
      return result;
    }

    void cancel(Throwable cause) {
      fail(cause);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
      try {
        if (message instanceof HttpResponse response) {
          handleResponse(context, response);
        }
        if (message instanceof HttpContent content) {
          handleContent(context, content);
        }
      } finally {
        ReferenceCountUtil.release(message);
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
      if (!finished.get()) {
        fail(new IOException("origin closed the connection before completing the response"));
      }
      context.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      fail(cause);
    }

    private void handleResponse(ChannelHandlerContext context, HttpResponse response) {
      if (!response.decoderResult().isSuccess()) {
        fail(new IOException("origin response decoder rejected the response"));
        return;
      }
      int code = response.status().code();
      if (code < 200) {
        if (code == 101) {
          fail(new IOException("origin protocol upgrades are not supported"));
        } else {
          context.read();
        }
        return;
      }
      if (spool != null) {
        fail(new IOException("origin sent more than one final response"));
        return;
      }
      if (code > 599) {
        fail(new IOException("origin returned an invalid HTTP status"));
        return;
      }
      long declaredLength;
      try {
        declaredLength = HttpUtil.getContentLength(response, -1);
      } catch (NumberFormatException exception) {
        fail(new IOException("origin Content-Length is malformed", exception));
        return;
      }
      if (declaredLength > request.maxResponseBytes()) {
        fail(new StreamingSpool.BodyLimitExceededException(request.maxResponseBytes()));
        return;
      }
      this.declaredLength = declaredLength;
      this.bodyForbidden =
          "HEAD".equals(request.method()) || code == 204 || code == 205 || code == 304;
      statusCode = code;
      metadata = HttpRequestSecurity.artifactMetadata(response.headers());
      reusable = HttpUtil.isKeepAlive(response);
      try {
        spool =
            new StreamingSpool(
                config.temporaryDirectory(),
                "origin-response-",
                request.maxResponseBytes(),
                spoolQuota);
      } catch (IOException | RuntimeException exception) {
        fail(exception);
        return;
      }
      context.read();
    }

    private void handleContent(ChannelHandlerContext context, HttpContent content) {
      if (spool == null) {
        fail(new IOException("origin sent content before a final response"));
        return;
      }
      if (!content.decoderResult().isSuccess()) {
        fail(new IOException("origin response content decoder rejected the response"));
        return;
      }
      int readableBytes = content.content().readableBytes();
      if (bodyForbidden && readableBytes != 0) {
        fail(new IOException("origin sent a body for a response that forbids one"));
        return;
      }
      byte[] bytes = new byte[readableBytes];
      content.content().getBytes(content.content().readerIndex(), bytes);
      boolean last = content instanceof LastHttpContent;
      try {
        spool
            .append(bytes)
            .whenComplete(
                (ignored, failure) -> {
                  if (failure != null) {
                    fail(unwrap(failure));
                  } else if (last) {
                    finishResponse();
                  } else if (!finished.get()) {
                    context.executor().execute(context::read);
                  }
                });
      } catch (RuntimeException exception) {
        fail(exception);
      }
    }

    private void finishResponse() {
      StreamingSpool current = spool;
      if (current == null) {
        fail(new IOException("origin response spool is unavailable"));
        return;
      }
      current
          .finish()
          .whenComplete(
              (spooled, failure) -> {
                if (failure != null) {
                  fail(unwrap(failure));
                  return;
                }
                if (!bodyForbidden && declaredLength >= 0 && declaredLength != spooled.length()) {
                  spooled.close();
                  fail(new IOException("origin response length did not match Content-Length"));
                  return;
                }
                if (!finished.compareAndSet(false, true)) {
                  spooled.close();
                  return;
                }
                cancelTimeout();
                TemporaryArtifactBody body = new TemporaryArtifactBody(spooled);
                Artifact artifact = new Artifact(statusCode, spooled.length(), metadata, body);
                metrics.originResponseBytes(spooled.length());
                detachAndRelease(reusable);
                result.complete(artifact);
              });
    }

    private void fail(Throwable cause) {
      if (!finished.compareAndSet(false, true)) {
        return;
      }
      cancelTimeout();
      StreamingSpool current = spool;
      if (current != null) {
        current.close();
      }
      detachAndRelease(false);
      result.completeExceptionally(cause);
    }

    private void detachAndRelease(boolean canReuse) {
      Channel channel = lease.channel();
      Runnable release =
          () -> {
            if (channel.pipeline().context(this) != null) {
              channel.pipeline().remove(this);
            }
            if (!canReuse) {
              lease.invalidate();
            }
            lease.close();
          };
      if (channel.eventLoop().inEventLoop()) {
        release.run();
      } else {
        channel.eventLoop().execute(release);
      }
    }

    private void cancelTimeout() {
      ScheduledFuture<?> current = timeout;
      if (current != null) {
        current.cancel(false);
        timeout = null;
      }
    }
  }
}
