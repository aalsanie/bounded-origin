package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayListenerLifecycleTest {
  @TempDir Path temporaryDirectory;

  @Test
  void closeReleasesBothClientAndAdminListeners() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);
    BoundedOriginGateway gateway =
        new BoundedOriginGateway(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
            new GatewayTestFixtures.MemoryArtifactStore());

    int clientPort;
    int adminPort;
    gateway.start();
    clientPort = gateway.listenAddress().getPort();
    adminPort = gateway.adminAddress().getPort();
    gateway.close();

    assertBindable(clientPort);
    assertBindable(adminPort);
  }

  @Test
  void adminBindFailureReleasesAlreadyBoundClientListener() throws Exception {
    int clientPort = GatewayTestFixtures.unusedPort();
    try (ServerSocket occupiedAdmin = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              GatewayTestFixtures.unusedPort(),
              temporaryDirectory,
              Map.of(
                  "listen.port",
                  Integer.toString(clientPort),
                  "admin.port",
                  Integer.toString(occupiedAdmin.getLocalPort())));
      BoundedOriginGateway gateway =
          new BoundedOriginGateway(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore());
      try {
        assertThrows(IOException.class, gateway::start);
      } finally {
        gateway.close();
      }
    }

    assertBindable(clientPort);
  }

  private static void assertBindable(int port) throws IOException {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }
  }
}
