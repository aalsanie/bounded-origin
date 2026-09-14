package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedOriginGatewayLifecycleTest {
  @TempDir Path temporaryDirectory;

  @Test
  void lifecycleRejectsInvalidTransitionsAndCloseIsIdempotent() throws Exception {
    int originPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config = GatewayTestFixtures.config(originPort, temporaryDirectory);
    BoundedOriginGateway gateway =
        new BoundedOriginGateway(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore());
    assertThrows(IllegalStateException.class, gateway::listenAddress);
    assertThrows(IllegalStateException.class, gateway::adminAddress);
    gateway.start();
    assertTrue(gateway.isReady());
    assertThrows(IllegalStateException.class, gateway::start);
    gateway.close();
    gateway.close();
    assertFalse(gateway.isReady());
    assertThrows(IllegalStateException.class, gateway::start);
  }

  @Test
  void closeBeforeStartPermanentlyClosesGateway() throws Exception {
    int originPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config = GatewayTestFixtures.config(originPort, temporaryDirectory);
    BoundedOriginGateway gateway =
        new BoundedOriginGateway(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore());
    gateway.close();
    assertThrows(IllegalStateException.class, gateway::start);
  }

  @Test
  void startupFailureReleasesRuntimeResourcesAndClosesGateway() throws Exception {
    int originPort = GatewayTestFixtures.unusedPort();
    Path file = temporaryDirectory.resolve("not-a-directory");
    Files.writeString(file, "x");
    GatewayConfig config = GatewayTestFixtures.config(originPort, file);
    BoundedOriginGateway gateway =
        new BoundedOriginGateway(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore());
    assertThrows(IOException.class, gateway::start);
    assertFalse(gateway.isReady());
    assertThrows(IllegalStateException.class, gateway::start);
    gateway.close();
  }

  @Test
  void bindFailureClosesPartiallyStartedGateway() throws Exception {
    int originPort = GatewayTestFixtures.unusedPort();
    try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              originPort,
              temporaryDirectory,
              Map.of("listen.port", Integer.toString(occupied.getLocalPort())));
      BoundedOriginGateway gateway =
          new BoundedOriginGateway(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore());
      assertThrows(IOException.class, gateway::start);
      assertFalse(gateway.isReady());
      gateway.close();
    }
  }

  @Test
  void shutdownWaitsForMaterializationAfterRequesterDisconnects() throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);

    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/materialize",
          (request, socket) -> {
            originEntered.countDown();
            if (!TestOriginServer.await(releaseOrigin, Duration.ofSeconds(5))) {
              throw new IOException("test origin release timed out");
            }
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: 2\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n"
                    + "ok");
            return true;
          });

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.max-execution-duration",
                  "PT4S",
                  "origin.response-timeout",
                  "PT4S",
                  "request.timeout",
                  "PT5S",
                  "drain.timeout",
                  "PT3S"));
      GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
      AtomicBoolean disconnectExpected = new AtomicBoolean();

      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.materializePolicy(config.globalBudget())),
                  store);
          var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
        RawHttpClient client = new RawHttpClient(gateway.listenAddress());
        try {
          Future<?> requester =
              tasks.submit(
                  () -> {
                    try {
                      client.request(
                          "GET",
                          "/materialize",
                          Map.of("Host", "example.test", "Connection", "keep-alive"),
                          new byte[0]);
                    } catch (IOException exception) {
                      if (!disconnectExpected.get()) {
                        throw new AssertionError(
                            "client failed before intentional disconnect", exception);
                      }
                    }
                  });

          assertTrue(originEntered.await(2, TimeUnit.SECONDS));
          disconnectExpected.set(true);
          client.close();

          awaitMetric(
              gateway.adminAddress(), "bounded_origin_active_requests", 0, Duration.ofSeconds(2));
          awaitMetric(
              gateway.adminAddress(), "bounded_origin_origin_active", 1, Duration.ofSeconds(2));

          Future<?> closing = tasks.submit(gateway::close);
          Thread.sleep(150);
          assertFalse(closing.isDone());

          releaseOrigin.countDown();
          closing.get(3, TimeUnit.SECONDS);
          requester.get(2, TimeUnit.SECONDS);

          assertEquals(1, origin.requests());
          assertEquals(1, store.size());
          assertEquals(0, gateway.trackedFlights());
        } finally {
          client.close();
        }
      } finally {
        releaseOrigin.countDown();
      }
    }
  }

  private static void awaitMetric(
      java.net.InetSocketAddress adminAddress, String name, long expected, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    AssertionError lastFailure = null;
    while (System.nanoTime() - deadline < 0) {
      try (RawHttpClient admin = new RawHttpClient(adminAddress)) {
        RawHttpClient.Response response =
            admin.request("GET", "/metrics", Map.of("Host", "admin"), new byte[0]);
        if (response.status() == 200) {
          long actual = metric(response.bodyText(), name);
          if (actual == expected) {
            return;
          }
          lastFailure = new AssertionError(name + " expected " + expected + " but was " + actual);
        }
      } catch (IOException exception) {
        lastFailure = new AssertionError("admin metrics request failed", exception);
      }
      Thread.sleep(10);
    }

    if (lastFailure != null) {
      throw lastFailure;
    }
    throw new AssertionError("metric " + name + " did not reach " + expected);
  }

  private static long metric(String prometheus, String name) {
    String prefix = name + " ";
    for (String line : prometheus.lines().toList()) {
      if (line.startsWith(prefix)) {
        return Long.parseLong(line.substring(prefix.length()));
      }
    }
    throw new AssertionError("missing metric " + name);
  }
}
