package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayDrainTimeoutTest {
  @TempDir Path temporaryDirectory;

  @Test
  void drainTimeoutForcesActiveClientClosedWithoutWaitingForOrigin() throws Exception {
    CountDownLatch originEntered = new CountDownLatch(1);
    CountDownLatch releaseOrigin = new CountDownLatch(1);

    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/hold",
          (request, socket) -> {
            originEntered.countDown();
            TestOriginServer.await(releaseOrigin, Duration.ofSeconds(5));
            return false;
          });

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "origin.max-execution-duration", "PT4S",
                  "origin.response-timeout", "PT4S",
                  "request.timeout", "PT5S",
                  "drain.timeout", "PT0.1S"));

      BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore());
      AtomicReference<Throwable> requesterFailure = new AtomicReference<>();

      try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> requester =
            tasks.submit(
                () -> {
                  try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
                    client.request(
                        "GET",
                        "/hold",
                        Map.of("Host", "example.test", "Connection", "keep-alive"),
                        new byte[0]);
                  } catch (Throwable throwable) {
                    requesterFailure.set(throwable);
                  }
                });

        assertTrue(originEntered.await(2, TimeUnit.SECONDS));

        gateway.close();

        requester.get(2, TimeUnit.SECONDS);
        assertFalse(gateway.isReady());
        assertNotNull(requesterFailure.get());
      } finally {
        releaseOrigin.countDown();
        gateway.close();
      }
    }
  }
}
