package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
}
