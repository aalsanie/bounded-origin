package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.EventLoopGroup;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
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
      assertResourcesClosed(gateway);
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
    assertResourcesClosed(gateway);
  }

  private static void assertResourcesClosed(BoundedOriginGateway gateway) throws Exception {
    GatewayRuntimeState runtime = field(gateway, "runtime", GatewayRuntimeState.class);
    assertFalse(runtime.healthy());

    SpoolQuota quota = field(gateway, "spoolQuota", SpoolQuota.class);
    assertThrows(IllegalStateException.class, quota::openFile);

    NettyOriginClient originClient = field(gateway, "originClient", NettyOriginClient.class);
    Field poolField = NettyOriginClient.class.getDeclaredField("pool");
    poolField.setAccessible(true);
    OriginConnectionPool pool = (OriginConnectionPool) poolField.get(originClient);
    CompletionException closed =
        assertThrows(CompletionException.class, () -> pool.acquire().toCompletableFuture().join());
    assertInstanceOf(OriginConnectionPool.PoolClosedException.class, closed.getCause());

    assertTrue(field(gateway, "acceptorGroup", EventLoopGroup.class).isTerminated());
    assertTrue(field(gateway, "clientGroup", EventLoopGroup.class).isTerminated());
    assertTrue(field(gateway, "originGroup", EventLoopGroup.class).isTerminated());
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(owner));
  }

  private static void assertBindable(int port) throws IOException {
    try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
      assertFalse(socket.isClosed());
    }
  }
}
