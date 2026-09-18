package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredRuntimeTest {
  @TempDir Path temporaryDirectory;

  @Test
  void assemblesStartsAndClosesConfiguredRuntime() throws IOException, ConfigurationException {
    ConfigurationModel.RuntimeConfiguration configuration =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);

    ConfiguredRuntime runtime = ConfiguredRuntime.assemble(configuration);
    assertFalse(runtime.isReady());
    assertThrows(IllegalStateException.class, runtime::listenAddress);
    assertThrows(IllegalStateException.class, runtime::adminAddress);

    runtime.start();

    assertTrue(runtime.isReady());
    assertTrue(runtime.listenAddress().getPort() > 0);
    assertTrue(runtime.adminAddress().getPort() > 0);
    assertNotEquals(runtime.listenAddress(), runtime.adminAddress());

    runtime.close();

    assertFalse(runtime.isReady());
    assertThrows(IllegalStateException.class, runtime::listenAddress);
    assertThrows(IllegalStateException.class, runtime::adminAddress);
    assertThrows(IllegalStateException.class, runtime::start);
    runtime.close();
  }

  @Test
  void rejectsNullAndInvalidTypedConfiguration() throws IOException, ConfigurationException {
    ConfigurationException nullConfiguration =
        assertThrows(ConfigurationException.class, () -> ConfiguredRuntime.assemble(null));
    assertTrue(nullConfiguration.getMessage().contains("configuration must not be null"));

    ConfigurationModel.RuntimeConfiguration base =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);
    Map<String, String> gateway = new LinkedHashMap<>(base.gateway());
    gateway.put("origin.max-active", "0");

    ConfigurationException invalidGateway =
        assertThrows(
            ConfigurationException.class,
            () ->
                ConfiguredRuntime.assemble(
                    new ConfigurationModel.RuntimeConfiguration(
                        base.schema(), gateway, base.store(), base.routes(), base.fallback())));
    assertTrue(invalidGateway.getMessage().contains("configuration.gateway is invalid"));
  }

  @Test
  void rejectsInvalidTypedStoreConfiguration() throws IOException, ConfigurationException {
    ConfigurationModel.RuntimeConfiguration base =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);

    assertStoreFailure(base, null, "configuration.store must not be null");
    assertStoreFailure(
        base,
        new ConfigurationModel.StoreConfiguration(" ", 1, 0),
        "configuration.store.directory must not be blank");
    assertStoreFailure(
        base,
        new ConfigurationModel.StoreConfiguration(
            temporaryDirectory.resolve("zero").toString(), 0, 0),
        "configuration.store.max-bytes must be positive");
    assertStoreFailure(
        base,
        new ConfigurationModel.StoreConfiguration(
            temporaryDirectory.resolve("negative").toString(), 1, -1),
        "configuration.store.max-artifact-bytes");
    assertStoreFailure(
        base,
        new ConfigurationModel.StoreConfiguration(
            temporaryDirectory.resolve("oversized").toString(), 1, 2),
        "configuration.store.max-artifact-bytes");
    assertStoreFailure(
        base,
        new ConfigurationModel.StoreConfiguration(String.valueOf('\0'), 1, 0),
        "configuration.store.directory is invalid");
  }

  @Test
  void rejectsMaterializationLimitAboveStoreArtifactLimit() throws IOException, ConfigurationException {
    ConfigurationModel.RuntimeConfiguration base =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);
    ConfigurationModel.StoreConfiguration store =
        new ConfigurationModel.StoreConfiguration(
            base.store().directory(), base.store().maxBytes(), 262_143);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () ->
                ConfiguredRuntime.assemble(
                    new ConfigurationModel.RuntimeConfiguration(
                        base.schema(), base.gateway(), store, base.routes(), base.fallback())));

    assertTrue(
        exception
            .getMessage()
            .contains(
                "configuration.routes[0].budget.max-result-bytes must not exceed configuration.store.max-artifact-bytes"));
  }

  @Test
  void storeLimitMayBeBelowGlobalLimitWhenNothingMaterializes() throws IOException, ConfigurationException {
    ConfigurationModel.RuntimeConfiguration base =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);
    List<ConfigurationModel.RouteConfiguration> routes =
        base.routes().stream()
            .filter(route -> route.strategy() != ConfigurationModel.Strategy.MATERIALIZE)
            .toList();
    ConfigurationModel.StoreConfiguration store =
        new ConfigurationModel.StoreConfiguration(
            base.store().directory(), base.store().maxBytes(), 0);

    try (ConfiguredRuntime runtime =
        ConfiguredRuntime.assemble(
            new ConfigurationModel.RuntimeConfiguration(
                base.schema(), base.gateway(), store, routes, base.fallback()))) {
      assertFalse(runtime.isReady());
    }
  }

  @Test
  void ownsAndReleasesArtifactStoreLock() throws IOException, ConfigurationException {
    ConfigurationModel.RuntimeConfiguration configuration =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);
    ConfiguredRuntime first = ConfiguredRuntime.assemble(configuration);

    assertThrows(IOException.class, () -> ConfiguredRuntime.assemble(configuration));

    first.close();

    try (ConfiguredRuntime second = ConfiguredRuntime.assemble(configuration)) {
      assertFalse(second.isReady());
    }
  }

  @Test
  void failedStartClosesOwnedStore() throws IOException, ConfigurationException {
    try (ServerSocket blocker = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      ConfigurationModel.RuntimeConfiguration base =
          ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);
      Map<String, String> gateway = new LinkedHashMap<>(base.gateway());
      gateway.put("listen.port", Integer.toString(blocker.getLocalPort()));
      ConfigurationModel.RuntimeConfiguration configuration =
          new ConfigurationModel.RuntimeConfiguration(
              base.schema(), gateway, base.store(), base.routes(), base.fallback());

      ConfiguredRuntime runtime = ConfiguredRuntime.assemble(configuration);
      assertThrows(IOException.class, runtime::start);
      assertFalse(runtime.isReady());
      runtime.close();

      try (ConfiguredRuntime replacement = ConfiguredRuntime.assemble(configuration)) {
        assertFalse(replacement.isReady());
      }
    }
  }

  @Test
  void rejectsSecondStart() throws IOException, ConfigurationException {
    ConfigurationModel.RuntimeConfiguration configuration =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);

    try (ConfiguredRuntime runtime = ConfiguredRuntime.assemble(configuration)) {
      runtime.start();
      assertThrows(IllegalStateException.class, runtime::start);
    }
  }

  private static void assertStoreFailure(
      ConfigurationModel.RuntimeConfiguration base,
      ConfigurationModel.StoreConfiguration store,
      String message)
      throws IOException {
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () ->
                ConfiguredRuntime.assemble(
                    new ConfigurationModel.RuntimeConfiguration(
                        base.schema(), base.gateway(), store, base.routes(), base.fallback())));
    assertTrue(exception.getMessage().contains(message));
  }
}
