package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayLifecycleResourceMutationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void adminBindFailureReleasesTheAlreadyBoundClientListener() throws Exception {
    int listenPort = GatewayTestFixtures.unusedPort();
    int originPort = GatewayTestFixtures.unusedPort();
    try (ServerSocket occupiedAdmin = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              originPort,
              temporaryDirectory,
              Map.of(
                  "listen.port", Integer.toString(listenPort),
                  "admin.port", Integer.toString(occupiedAdmin.getLocalPort())));
      BoundedOriginGateway gateway =
          new BoundedOriginGateway(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore());

      assertThrows(IOException.class, gateway::start);
      assertFalse(gateway.isReady());
      gateway.close();

      assertBindable(listenPort);
    }
  }

  @Test
  void closeReleasesBothPublicAndAdministrativeListeners() throws Exception {
    int listenPort = GatewayTestFixtures.unusedPort();
    int adminPort = GatewayTestFixtures.unusedPort();
    int originPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config =
        GatewayTestFixtures.config(
            originPort,
            temporaryDirectory,
            Map.of(
                "listen.port", Integer.toString(listenPort),
                "admin.port", Integer.toString(adminPort)));
    BoundedOriginGateway gateway =
        new BoundedOriginGateway(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore());

    gateway.start();
    gateway.close();

    assertBindable(listenPort);
    assertBindable(adminPort);
  }

  private static void assertBindable(int port) throws IOException {
    try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
      assertFalse(socket.isClosed());
    }
  }
}
