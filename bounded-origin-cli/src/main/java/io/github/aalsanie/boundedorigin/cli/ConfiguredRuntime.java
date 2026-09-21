package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.proxy.BoundedOriginGateway;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.proxy.OriginCompletionContract;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

final class ConfiguredRuntime implements AutoCloseable {
  private final Object lifecycleLock = new Object();
  private final BoundedOriginGateway gateway;
  private final FileSystemArtifactStore store;
  private boolean started;
  private boolean closed;

  private ConfiguredRuntime(BoundedOriginGateway gateway, FileSystemArtifactStore store) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.store = Objects.requireNonNull(store, "store");
  }

  static void validate(ConfigurationModel.RuntimeConfiguration configuration)
      throws ConfigurationException {
    ConfigurationModel.RuntimeConfiguration required = requireConfiguration(configuration);
    GatewayConfig gatewayConfig = gatewayConfig(required);
    PolicyConfigurationCompiler.compile(required, gatewayConfig.globalBudget());
    validateOriginContract(required, gatewayConfig);
    ConfigurationModel.StoreConfiguration storeConfiguration = validateStore(required.store());
    Path storeDirectory = storePath(storeConfiguration.directory()).toAbsolutePath().normalize();
    if (gatewayConfig.originOwnershipDirectory().isPresent()) {
      validateStorageSeparation(
          storeDirectory, gatewayConfig.originOwnershipDirectory().orElseThrow());
    }
    validateStoreCompatibility(required, storeConfiguration);
  }

  static ConfiguredRuntime assemble(ConfigurationModel.RuntimeConfiguration configuration)
      throws ConfigurationException, IOException {
    ConfigurationModel.RuntimeConfiguration required = requireConfiguration(configuration);
    GatewayConfig gatewayConfig = gatewayConfig(required);
    PolicyEngine policyEngine =
        PolicyConfigurationCompiler.compile(required, gatewayConfig.globalBudget());
    validateOriginContract(required, gatewayConfig);
    ConfigurationModel.StoreConfiguration storeConfiguration = validateStore(required.store());
    Path storeDirectory = storePath(storeConfiguration.directory());
    validateStoreCompatibility(required, storeConfiguration);
    if (gatewayConfig.originOwnershipDirectory().isPresent()) {
      validateStorageSeparation(
          resolveExistingParents(storeDirectory),
          resolveExistingParents(gatewayConfig.originOwnershipDirectory().orElseThrow()));
    }
    FileSystemArtifactStore store =
        new FileSystemArtifactStore(
            storeDirectory, storeConfiguration.maxBytes(), storeConfiguration.maxArtifactBytes());
    try {
      return new ConfiguredRuntime(
          new BoundedOriginGateway(gatewayConfig, policyEngine, store), store);
    } catch (RuntimeException | Error exception) {
      closeAfterAssemblyFailure(store, exception);
      throw exception;
    }
  }

  void start() throws IOException {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("runtime is closed");
      }
      if (started) {
        throw new IllegalStateException("runtime is already started");
      }
      try {
        gateway.start();
        started = true;
      } catch (IOException | RuntimeException | Error exception) {
        closed = true;
        closeAfterStartFailure(exception);
        throw exception;
      }
    }
  }

  boolean isReady() {
    synchronized (lifecycleLock) {
      return started && !closed && gateway.isReady();
    }
  }

  InetSocketAddress listenAddress() {
    synchronized (lifecycleLock) {
      if (!started || closed) {
        throw new IllegalStateException("runtime is not running");
      }
      return gateway.listenAddress();
    }
  }

  InetSocketAddress adminAddress() {
    synchronized (lifecycleLock) {
      if (!started || closed) {
        throw new IllegalStateException("runtime is not running");
      }
      return gateway.adminAddress();
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      closeResources();
    }
  }

  private static ConfigurationModel.RuntimeConfiguration requireConfiguration(
      ConfigurationModel.RuntimeConfiguration configuration) throws ConfigurationException {
    if (configuration == null) {
      throw new ConfigurationException("configuration must not be null");
    }
    return configuration;
  }

  private static GatewayConfig gatewayConfig(ConfigurationModel.RuntimeConfiguration configuration)
      throws ConfigurationException {
    try {
      return GatewayConfig.from(configuration.gateway());
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException("configuration.gateway is invalid", exception);
    }
  }

  private static ConfigurationModel.StoreConfiguration validateStore(
      ConfigurationModel.StoreConfiguration store) throws ConfigurationException {
    if (store == null) {
      throw new ConfigurationException("configuration.store must not be null");
    }
    if (store.directory() == null || store.directory().isBlank()) {
      throw new ConfigurationException("configuration.store.directory must not be blank");
    }
    if (store.maxBytes() <= 0) {
      throw new ConfigurationException("configuration.store.max-bytes must be positive");
    }
    if (store.maxArtifactBytes() < 0 || store.maxArtifactBytes() > store.maxBytes()) {
      throw new ConfigurationException(
          "configuration.store.max-artifact-bytes must be non-negative and not exceed max-bytes");
    }
    return store;
  }

  private static void validateOriginContract(
      ConfigurationModel.RuntimeConfiguration configuration, GatewayConfig gateway)
      throws ConfigurationException {
    for (ConfigurationModel.RouteConfiguration route : configuration.routes()) {
      if ((route.strategy() == ConfigurationModel.Strategy.BOUNDED_COMPUTE
              || route.strategy() == ConfigurationModel.Strategy.MATERIALIZE)
          && gateway.originCompletionContract() == OriginCompletionContract.DISABLED) {
        throw new ConfigurationException(
            "computation routes require origin.completion-contract: RESPONSE_COMPLETE and a persistent origin.ownership-directory");
      }
    }
  }

  private static Path storePath(String directory) throws ConfigurationException {
    try {
      return Path.of(directory);
    } catch (InvalidPathException exception) {
      throw new ConfigurationException("configuration.store.directory is invalid", exception);
    }
  }

  private static void validateStorageSeparation(Path store, Path ownership)
      throws ConfigurationException {
    if (store.startsWith(ownership) || ownership.startsWith(store)) {
      throw new ConfigurationException(
          "origin.ownership-directory and store.directory must not overlap");
    }
  }

  private static Path resolveExistingParents(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path existing = absolute;
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
      if (existing == null) {
        throw new IOException("storage path has no accessible parent");
      }
    }
    return existing.toRealPath().resolve(existing.relativize(absolute));
  }

  private static void validateStoreCompatibility(
      ConfigurationModel.RuntimeConfiguration configuration,
      ConfigurationModel.StoreConfiguration store)
      throws ConfigurationException {
    for (int index = 0; index < configuration.routes().size(); index++) {
      ConfigurationModel.RouteConfiguration route = configuration.routes().get(index);
      if (route.strategy() != ConfigurationModel.Strategy.MATERIALIZE) {
        continue;
      }
      long resultLimit = route.budget().orElseThrow().maxResultBytes();
      if (resultLimit > store.maxArtifactBytes()) {
        throw new ConfigurationException(
            "configuration.routes["
                + index
                + "].budget.max-result-bytes must not exceed configuration.store.max-artifact-bytes");
      }
    }
  }

  private void closeAfterStartFailure(Throwable failure) {
    try {
      closeResources();
    } catch (IOException | RuntimeException | Error closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }

  private void closeResources() throws IOException {
    try (store) {
      gateway.close();
    }
  }

  private static void closeAfterAssemblyFailure(FileSystemArtifactStore store, Throwable failure) {
    try {
      store.close();
    } catch (IOException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }
}
