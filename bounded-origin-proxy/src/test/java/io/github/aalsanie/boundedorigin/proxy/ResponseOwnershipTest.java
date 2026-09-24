package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import io.netty.buffer.Unpooled;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResponseOwnershipTest {
  @TempDir Path root;

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void responseWriterOwnsUnopenedBodyAcrossDisconnectAndStoreShutdown(
      boolean persisted, boolean shutdown) throws Exception {
    GatewayConfig config = GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), root);
    GatewayMetrics metrics = new GatewayMetrics();
    GatewayRuntimeState runtime = new GatewayRuntimeState(config.maxClientConnections());
    runtime.started();
    var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    var quota = new SpoolQuota(config.maxSpoolBytes(), config.maxSpoolFiles());
    var store = new FileSystemArtifactStore(root.resolve("store"), 300, 100);
    var key = new OperationKey("p", 1, "stored", "v1");
    ArtifactBody owned;
    if (persisted) {
      store.put(key, ResponseArtifacts.text(200, "payload"));
      owned = store.get(key).orElseThrow().body();
    } else {
      var spool = new StreamingSpool(root, "response-", 1024, quota);
      spool
          .append("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))
          .toCompletableFuture()
          .join();
      owned = new TemporaryArtifactBody(spool.finish().toCompletableFuture().join());
    }
    BarrierBody body = new BarrierBody(owned);
    Artifact artifact = new Artifact(7, Map.of("cache-control", "public"), body);
    ArtifactStore lookup =
        new ArtifactStore() {
          @Override
          public Optional<Artifact> get(OperationKey ignored) {
            return Optional.of(artifact);
          }

          @Override
          public void put(OperationKey ignored, Artifact value) {
            throw new AssertionError();
          }
        };
    var executor =
        new BoundedOriginExecutor(
            config.globalBudget(), config.failureCooldown(), config.maxCooldownEntries());
    var client = new NettyOriginClient(group, config, metrics, quota);
    var originWork = new OriginWorkRegistry(config, metrics);
    var flights = new FlightLeaseRegistry(metrics);
    var processor =
        new GatewayRequestProcessor(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
            lookup,
            executor,
            client,
            metrics,
            flights,
            originWork);
    var handler =
        new GatewayRequestHandler(
            config,
            processor,
            new ArtifactResponseWriter(config, metrics),
            metrics,
            runtime,
            quota);
    var channel = new EmbeddedChannel(handler);
    try {
      var request =
          new DefaultFullHttpRequest(
              HttpVersion.HTTP_1_1, HttpMethod.GET, "/one", Unpooled.EMPTY_BUFFER);
      request.headers().set("host", "example.test").set("content-length", "0");
      channel.writeInbound(request);
      await(channel, () -> body.entered.getCount() == 0);
      channel.close();
      await(channel, () -> runtime.activeRequests() == 0);
      if (shutdown) {
        executor.close();
        store.close();
      }
      assertEquals(0, body.closes.get());
      assertEquals(1, flights.trackedFlights());
      body.release.countDown();
      await(channel, () -> body.closed.getCount() == 0);
      await(channel, () -> flights.trackedFlights() == 0);
      assertEquals(7, body.read.get());
      assertEquals(1, body.closes.get());
      assertEquals(1, body.streamCloses.get());
      assertEquals(0, runtime.activeRequests());
    } finally {
      body.release.countDown();
      channel.close();
      await(channel, () -> flights.trackedFlights() == 0);
      channel.finishAndReleaseAll();
      owned.close();
      executor.close();
      client.close();
      originWork.close();
      quota.close();
      store.close();
      group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
    try (var reopened = new FileSystemArtifactStore(root.resolve("store"), 300, 100)) {
      assertTrue(reopened.stats().storedBytes() <= 300);
    }
  }

  private static void await(EmbeddedChannel channel, BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      channel.runPendingTasks();
      Thread.onSpinWait();
    }
    assertTrue(condition.getAsBoolean());
  }

  private static final class BarrierBody implements ArtifactBody {
    private final ArtifactBody delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicInteger read = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger streamCloses = new AtomicInteger();

    private BarrierBody(ArtifactBody delegate) {
      this.delegate = delegate;
    }

    @Override
    public InputStream openStream() throws IOException {
      entered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IOException("body open barrier expired");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException(exception);
      }
      return new FilterInputStream(delegate.openStream()) {
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
          int count = super.read(bytes, offset, length);
          if (count > 0) {
            read.addAndGet(count);
          }
          return count;
        }

        @Override
        public void close() throws IOException {
          super.close();
          streamCloses.incrementAndGet();
        }
      };
    }

    @Override
    public void close() throws IOException {
      closes.incrementAndGet();
      delegate.close();
      closed.countDown();
    }
  }
}
