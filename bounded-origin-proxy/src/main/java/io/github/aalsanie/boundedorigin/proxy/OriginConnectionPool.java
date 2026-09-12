package io.github.aalsanie.boundedorigin.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class OriginConnectionPool implements AutoCloseable {
  private final Object lock = new Object();
  private final Bootstrap bootstrap;
  private final InetSocketAddress originAddress;
  private final int maxConnections;
  private final int maxPendingAcquires;
  private final GatewayMetrics metrics;
  private final ArrayDeque<Channel> idle = new ArrayDeque<>();
  private final ArrayDeque<CompletableFuture<Lease>> pending = new ArrayDeque<>();
  private final Set<Channel> channels = Collections.newSetFromMap(new IdentityHashMap<>());

  private int reservations;
  private boolean closed;

  OriginConnectionPool(EventLoopGroup group, GatewayConfig config, GatewayMetrics metrics) {
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(config, "config");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.originAddress = config.originAddress();
    this.maxConnections = config.originMaxConnections();
    this.maxPendingAcquires = config.originMaxPendingAcquires();

    HttpDecoderConfig decoderConfig = strictDecoderConfig(config);
    WriteBufferWaterMark waterMark =
        new WriteBufferWaterMark(
            config.writeBufferLowWaterMark(), config.writeBufferHighWaterMark());
    this.bootstrap =
        new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.AUTO_READ, false)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS, durationMillis(config.originConnectTimeout()))
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
            .option(
                ChannelOption.RECVBUF_ALLOCATOR,
                new FixedRecvByteBufAllocator(config.maxChunkSize()))
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel channel) {
                    channel
                        .pipeline()
                        .addLast(new HttpClientCodec(decoderConfig.clone(), false, true))
                        .addLast(
                            new IdleStateHandler(
                                0, 0, config.idleTimeout().toNanos(), TimeUnit.NANOSECONDS))
                        .addLast(new IdleCloseHandler())
                        .addLast(new ChunkedWriteHandler());
                  }
                });
  }

  CompletionStage<Lease> acquire() {
    CompletableFuture<Lease> result = new CompletableFuture<>();
    boolean connect = false;
    synchronized (lock) {
      if (closed) {
        result.completeExceptionally(new PoolClosedException());
        return result;
      }
      while (!idle.isEmpty()) {
        Channel channel = idle.removeFirst();
        if (channel.isActive()) {
          result.complete(new Lease(this, channel));
          return result;
        }
      }
      pruneCompletedPendingLocked();
      if (reservations < maxConnections) {
        reservations++;
        connect = true;
      } else if (pending.size() < maxPendingAcquires) {
        pending.addLast(result);
      } else {
        metrics.originPoolRejection();
        result.completeExceptionally(new PoolExhaustedException());
      }
    }
    if (connect) {
      connect(result);
    }
    return result;
  }

  int openConnections() {
    synchronized (lock) {
      return channels.size();
    }
  }

  int pendingAcquires() {
    synchronized (lock) {
      pruneCompletedPendingLocked();
      return pending.size();
    }
  }

  @Override
  public void close() {
    List<CompletableFuture<Lease>> waiting;
    List<Channel> open;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      waiting = new ArrayList<>(pending);
      pending.clear();
      open = new ArrayList<>(channels);
      idle.clear();
    }
    PoolClosedException failure = new PoolClosedException();
    for (CompletableFuture<Lease> future : waiting) {
      future.completeExceptionally(failure);
    }
    for (Channel channel : open) {
      channel.close();
    }
  }

  private void connect(CompletableFuture<Lease> result) {
    ChannelFuture future = bootstrap.connect(originAddress);
    future.addListener(
        completed -> {
          ChannelFuture connection = (ChannelFuture) completed;
          if (!connection.isSuccess()) {
            onConnectFailure(result, connection.cause());
            return;
          }
          Channel channel = connection.channel();
          boolean reject;
          synchronized (lock) {
            reject = closed;
            if (!reject) {
              channels.add(channel);
            }
          }
          channel.closeFuture().addListener(ignored -> onChannelClosed(channel));
          if (reject) {
            channel.close();
            result.completeExceptionally(new PoolClosedException());
          } else {
            Lease lease = new Lease(this, channel);
            if (!result.complete(lease)) {
              lease.close();
            }
          }
        });
  }

  private void onConnectFailure(CompletableFuture<Lease> result, Throwable cause) {
    CompletableFuture<Lease> replacement = null;
    synchronized (lock) {
      reservations--;
      if (!closed && reservations < maxConnections) {
        replacement = pollPendingLocked();
        if (replacement != null) {
          reservations++;
        }
      }
    }
    result.completeExceptionally(
        cause == null ? new OriginConnectException("origin connection failed") : cause);
    if (replacement != null) {
      connect(replacement);
    }
  }

  private void onChannelClosed(Channel channel) {
    CompletableFuture<Lease> replacement = null;
    synchronized (lock) {
      idle.remove(channel);
      if (channels.remove(channel)) {
        reservations--;
      }
      if (!closed && reservations < maxConnections) {
        replacement = pollPendingLocked();
        if (replacement != null) {
          reservations++;
        }
      }
    }
    if (replacement != null) {
      connect(replacement);
    }
  }

  private void release(Channel channel, boolean reusable) {
    while (true) {
      CompletableFuture<Lease> waiter;
      synchronized (lock) {
        if (closed || !reusable || !channel.isActive()) {
          channel.close();
          return;
        }
        waiter = pollPendingLocked();
        if (waiter == null) {
          idle.addLast(channel);
          return;
        }
      }
      if (waiter.complete(new Lease(this, channel))) {
        return;
      }
    }
  }

  private CompletableFuture<Lease> pollPendingLocked() {
    while (!pending.isEmpty()) {
      CompletableFuture<Lease> candidate = pending.removeFirst();
      if (!candidate.isDone()) {
        return candidate;
      }
    }
    return null;
  }

  private void pruneCompletedPendingLocked() {
    pending.removeIf(CompletableFuture::isDone);
  }

  private static HttpDecoderConfig strictDecoderConfig(GatewayConfig config) {
    return new HttpDecoderConfig()
        .setMaxInitialLineLength(config.maxInitialLineLength())
        .setMaxHeaderSize(config.maxHeaderSize())
        .setMaxChunkSize(config.maxChunkSize())
        .setChunkedSupported(true)
        .setAllowPartialChunks(true)
        .setAllowDuplicateContentLengths(false)
        .setValidateHeaders(true)
        .setStrictLineParsing(true)
        .setUseRfc9112TransferEncoding(true);
  }

  private static int durationMillis(Duration duration) {
    long millis = Math.max(1, duration.toMillis());
    return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
  }

  static final class Lease implements AutoCloseable {
    private final OriginConnectionPool owner;
    private final Channel channel;
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile boolean reusable = true;

    private Lease(OriginConnectionPool owner, Channel channel) {
      this.owner = owner;
      this.channel = channel;
    }

    Channel channel() {
      return channel;
    }

    void invalidate() {
      reusable = false;
    }

    @Override
    public void close() {
      if (released.compareAndSet(false, true)) {
        owner.release(channel, reusable);
      }
    }
  }

  static final class PoolExhaustedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PoolExhaustedException() {
      super("origin connection pool is exhausted");
    }
  }

  static final class PoolClosedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PoolClosedException() {
      super("origin connection pool is closed");
    }
  }

  static final class OriginConnectException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    OriginConnectException(String message) {
      super(message);
    }
  }

  private static final class IdleCloseHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
      if (event instanceof IdleStateEvent) {
        context.close();
      } else {
        context.fireUserEventTriggered(event);
      }
    }
  }
}
