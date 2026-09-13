package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayDrainOrderMutationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void drainStopsPublicAcceptsBeforeWaitingButKeepsAdminObservableUntilWorkFinishes()
      throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/slow",
          (request, socket) -> {
            originEntered.countDown();
            assertTrue(TestOriginServer.await(releaseOrigin, Duration.ofSeconds(3)));
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.max-execution-duration", "PT3S",
                  "origin.response-timeout", "PT3S",
                  "request.timeout", "PT4S",
                  "drain.timeout", "PT2S"));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.materializePolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
        InetSocketAddress publicAddress = gateway.listenAddress();
        InetSocketAddress adminAddress = gateway.adminAddress();
        RawHttpClient client = new RawHttpClient(publicAddress);
        Future<?> request =
            tasks.submit(
                () -> {
                  try {
                    client.request(
                        "GET",
                        "/slow",
                        Map.of("Host", "example.test", "Connection", "keep-alive"),
                        new byte[0]);
                  } catch (IOException ignored) {
                  }
                });
        try {
          assertTrue(originEntered.await(1, TimeUnit.SECONDS));
          client.close();

          Future<?> closing = tasks.submit(gateway::close);
          awaitNotReady(adminAddress);
          assertFalse(closing.isDone());
          assertPublicListenerClosed(publicAddress);

          try (RawHttpClient admin = new RawHttpClient(adminAddress)) {
            RawHttpClient.Response health =
                admin.request("GET", "/health", Map.of("Host", "admin"), new byte[0]);
            assertEquals(200, health.status());
          }

          releaseOrigin.countDown();
          closing.get(3, TimeUnit.SECONDS);
          request.get(2, TimeUnit.SECONDS);
          assertThrows(IOException.class, () -> new RawHttpClient(adminAddress));
        } finally {
          releaseOrigin.countDown();
          client.close();
        }
      } finally {
        releaseOrigin.countDown();
      }
    }
  }

  private static void awaitNotReady(InetSocketAddress adminAddress) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (System.nanoTime() - deadline < 0) {
      try (RawHttpClient admin = new RawHttpClient(adminAddress)) {
        RawHttpClient.Response response =
            admin.request("GET", "/ready", Map.of("Host", "admin"), new byte[0]);
        if (response.status() == 503) {
          assertEquals("draining\n", response.bodyText());
          return;
        }
      }
      Thread.sleep(5);
    }
    throw new AssertionError("gateway did not enter draining state");
  }

  private static void assertPublicListenerClosed(InetSocketAddress address) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (System.nanoTime() - deadline < 0) {
      try (Socket socket = new Socket()) {
        socket.connect(address, 50);
      } catch (IOException expected) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("public listener remained open during drain");
  }
}
