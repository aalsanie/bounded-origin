package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class BoundedOriginGateway implements AutoCloseable {
  private final Object lifecycleLock = new Object();
  private final GatewayConfig config;
  private final BoundedOriginExecutor executor;
  private final GatewayMetrics metrics = new GatewayMetrics();
  private final GatewayRuntimeState runtime;
  private final SpoolQuota spoolQuota;
  private final EventLoopGroup acceptorGroup;
  private final EventLoopGroup clientGroup;
  private final EventLoopGroup originGroup;
  private final NettyOriginClient originClient;
  private final FlightLeaseRegistry flights;
  private final GatewayRequestProcessor processor;
  private final ArtifactResponseWriter responseWriter;

  private Channel clientServer;
  private Channel adminServer;
  private boolean started;
  private boolean closed;

  public BoundedOriginGateway(
      GatewayConfig config, PolicyEngine policyEngine, ArtifactStore artifactStore) {
    this.config = Objects.requireNonNull(config, "config");
    Objects.requireNonNull(policyEngine, "policyEngine");
    Objects.requireNonNull(artifactStore, "artifactStore");
    this.executor =
        new BoundedOriginExecutor(
            config.globalBudget(), config.failureCooldown(), config.maxCooldownEntries());
    this.runtime = new GatewayRuntimeState(config.maxClientConnections());
    this.spoolQuota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    this.acceptorGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    this.clientGroup =
        new MultiThreadIoEventLoopGroup(config.eventLoopThreads(), NioIoHandler.newFactory());
    this.originGroup =
        new MultiThreadIoEventLoopGroup(config.originEventLoopThreads(), NioIoHandler.newFactory());
    this.originClient = new NettyOriginClient(originGroup, config, metrics, spoolQuota);
    this.flights = new FlightLeaseRegistry(metrics);
    this.processor =
        new GatewayRequestProcessor(
            config, policyEngine, artifactStore, executor, originClient, metrics, flights);
    this.responseWriter = new ArtifactResponseWriter(config, metrics);
  }

  public void start() throws IOException {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("gateway is closed");
      }
      if (started) {
        throw new IllegalStateException("gateway is already started");
      }
      Files.createDirectories(config.temporaryDirectory());
      runtime.started();
      try {
        clientServer = bindClient();
        adminServer = bindAdmin();
        started = true;
      } catch (RuntimeException exception) {
        runtime.beginDrain();
        closeChannel(clientServer);
        closeChannel(adminServer);
        shutdownResources();
        closed = true;
        runtime.closed();
        throw new IOException("failed to bind bounded-origin gateway", exception);
      }
      StructuredLog.started(
          listenAddress().toString(), adminAddress().toString(), config.originAddress().toString());
    }
  }

  public InetSocketAddress listenAddress() {
    synchronized (lifecycleLock) {
      return localAddress(clientServer, "gateway is not started");
    }
  }

  public InetSocketAddress adminAddress() {
    synchronized (lifecycleLock) {
      return localAddress(adminServer, "gateway is not started");
    }
  }

  public boolean isReady() {
    return runtime.ready();
  }

  @Override
  public void close() {
    boolean logStopped = false;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      logStopped = started;
      runtime.beginDrain();
      closeChannel(clientServer);
    }

    boolean drained = runtime.awaitDrained(config.drainTimeout());
    if (!drained) {
      for (Channel channel : runtime.clients()) {
        channel.close();
      }
      runtime.awaitDrained(java.time.Duration.ofMillis(250));
    }
    for (Channel channel : runtime.clients()) {
      channel.close();
    }

    synchronized (lifecycleLock) {
      closeChannel(adminServer);
      shutdownResources();
      runtime.closed();
      if (logStopped) {
        StructuredLog.stopped();
      }
    }
  }

  int trackedFlights() {
    return flights.trackedFlights();
  }

  private Channel bindClient() {
    HttpDecoderConfig decoder = strictDecoderConfig();
    WriteBufferWaterMark waterMark =
        new WriteBufferWaterMark(
            config.writeBufferLowWaterMark(), config.writeBufferHighWaterMark());
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(acceptorGroup, clientGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, config.serverBacklog())
            .childOption(ChannelOption.AUTO_READ, false)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
            .childOption(
                ChannelOption.RCVBUF_ALLOCATOR,
                new FixedRecvByteBufAllocator(config.maxChunkSize()))
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel channel) {
                    channel
                        .pipeline()
                        .addLast(new HttpServerCodec(decoder.clone(), 1))
                        .addLast(
                            new IdleStateHandler(
                                config.idleTimeout().toNanos(), 0, 0, TimeUnit.NANOSECONDS))
                        .addLast(
                            new GatewayRequestHandler(
                                config, processor, responseWriter, metrics, runtime, spoolQuota));
                  }
                });
    return bind(bootstrap, config.listenAddress());
  }

  private Channel bindAdmin() {
    HttpDecoderConfig decoder =
        new HttpDecoderConfig()
            .setMaxInitialLineLength(1_024)
            .setMaxHeaderSize(4_096)
            .setMaxChunkSize(1_024)
            .setChunkedSupported(false)
            .setAllowDuplicateContentLengths(false)
            .setValidateHeaders(true)
            .setStrictLineParsing(true)
            .setUseRfc9112TransferEncoding(true);
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(acceptorGroup, clientGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.AUTO_READ, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel channel) {
                    channel
                        .pipeline()
                        .addLast(new HttpServerCodec(decoder.clone(), 1))
                        .addLast(
                            new AdminHandler(runtime, metrics, executor, originClient, spoolQuota));
                  }
                });
    return bind(bootstrap, config.adminAddress());
  }

  private HttpDecoderConfig strictDecoderConfig() {
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

  private static Channel bind(ServerBootstrap bootstrap, InetSocketAddress address) {
    ChannelFuture future = bootstrap.bind(address).syncUninterruptibly();
    if (!future.isSuccess()) {
      throw new IllegalStateException("server bind failed", future.cause());
    }
    return future.channel();
  }

  private static InetSocketAddress localAddress(Channel channel, String message) {
    if (channel == null) {
      throw new IllegalStateException(message);
    }
    return (InetSocketAddress) channel.localAddress();
  }

  private static void closeChannel(Channel channel) {
    if (channel != null) {
      channel.close().syncUninterruptibly();
    }
  }

  private void shutdownResources() {
    executor.close();
    originClient.close();
    spoolQuota.close();
    shutdown(originGroup);
    shutdown(clientGroup);
    shutdown(acceptorGroup);
  }

  private static void shutdown(EventLoopGroup group) {
    group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
  }
}
