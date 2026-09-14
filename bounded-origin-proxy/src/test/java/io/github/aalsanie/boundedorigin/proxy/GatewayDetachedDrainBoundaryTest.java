package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayDetachedDrainBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void drainDeadlineExpiresWhileDetachedOriginWorkRemainsInFlight() throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);
    try (TestOriginServer origin = heldOrigin(originEntered, releaseOrigin)) {
      GatewayConfig config = config(origin.port(), "PT0.1S");
      BoundedOriginGateway gateway = start(config);
      RawHttpClient client = new RawHttpClient(gateway.listenAddress());
      Thread requester = requestInBackground(client);
      try {
        assertTrue(originEntered.await(2, TimeUnit.SECONDS));
        client.close();
        requester.join(Duration.ofSeconds(2));
        awaitActiveRequests(gateway, 0);
        awaitTrackedFlights(gateway, 1);

        gateway.close();
        assertFalse(gateway.isReady());
      } finally {
        releaseOrigin.countDown();
        client.close();
        gateway.close();
      }
    }
  }

  @Test
  void interruptingDrainStopsWaitingForDetachedOriginWork() throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);
    try (TestOriginServer origin = heldOrigin(originEntered, releaseOrigin)) {
      GatewayConfig config = config(origin.port(), "PT3S");
      BoundedOriginGateway gateway = start(config);
      RawHttpClient client = new RawHttpClient(gateway.listenAddress());
      Thread requester = requestInBackground(client);
      try {
        assertTrue(originEntered.await(2, TimeUnit.SECONDS));
        client.close();
        requester.join(Duration.ofSeconds(2));
        awaitActiveRequests(gateway, 0);
        awaitTrackedFlights(gateway, 1);

        Thread closing = Thread.ofVirtual().start(gateway::close);
        Thread.sleep(50);
        closing.interrupt();
        assertTrue(closing.join(Duration.ofSeconds(2)));
        assertFalse(gateway.isReady());
      } finally {
        releaseOrigin.countDown();
        client.close();
        gateway.close();
      }
    }
  }

  private TestOriginServer heldOrigin(CountDownLatch originEntered, CountDownLatch releaseOrigin)
      throws IOException {
    TestOriginServer origin = new TestOriginServer();
    origin.respond(
        "/held",
        (request, socket) -> {
          originEntered.countDown();
          TestOriginServer.await(releaseOrigin, Duration.ofSeconds(5));
          return false;
        });
    return origin;
  }

  private GatewayConfig config(int originPort, String drainTimeout) throws Exception {
    return GatewayTestFixtures.config(
        originPort,
        temporaryDirectory,
        Map.of(
            "origin.max-execution-duration", "PT4S",
            "origin.response-timeout", "PT4S",
            "request.timeout", "PT5S",
            "drain.timeout", drainTimeout));
  }

  private BoundedOriginGateway start(GatewayConfig config) throws IOException {
    return GatewayTestFixtures.start(
        config,
        GatewayTestFixtures.engine(GatewayTestFixtures.materializePolicy(config.globalBudget())),
        new GatewayTestFixtures.MemoryArtifactStore());
  }

  private static Thread requestInBackground(RawHttpClient client) {
    return Thread.ofVirtual()
        .start(
            () -> {
              try {
                client.request(
                    "GET",
                    "/held",
                    Map.of("Host", "example.test", "Connection", "keep-alive"),
                    new byte[0]);
              } catch (IOException ignored) {
              }
            });
  }

  private static void awaitActiveRequests(BoundedOriginGateway gateway, long expected)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      try (RawHttpClient admin = new RawHttpClient(gateway.adminAddress())) {
        RawHttpClient.Response response =
            admin.request("GET", "/metrics", Map.of("Host", "admin"), new byte[0]);
        if (response.status() == 200
            && metric(response.bodyText(), "bounded_origin_active_requests") == expected) {
          return;
        }
      }
      Thread.sleep(5);
    }
    throw new AssertionError("active requests did not reach " + expected);
  }

  private static void awaitTrackedFlights(BoundedOriginGateway gateway, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (gateway.trackedFlights() == expected) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("tracked flights did not reach " + expected);
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
