package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.proxy.BoundedOriginGateway;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

final class ConfiguredRuntime implements AutoCloseable {
  private final BoundedOriginGateway gateway;
  private final FileSystemArtifactStore store;
  private boolean started;
  private boolean closed;

  private ConfiguredRuntime(BoundedOriginGateway gateway, FileSystemArtifactStore store) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.store = Objects.requireNonNull(store, "store");
  }

  static ConfiguredRuntime assemble(ConfigurationModel.RuntimeConfiguration configuration)
      throws ConfigurationException, IOException {
    if (configuration == null) {
      throw new ConfigurationException("configuration must not be null");
    }

    GatewayConfig gatewayConfig = gatewayConfig(configuration);
    PolicyEngine policyEngine =
        PolicyConfigurationCompiler.compile(configuration, gatewayConfig.globalBudget());
    ConfigurationModel.StoreConfiguration storeConfiguration = validateStore(configuration.store());
    Path storeDirectory = storePath(storeConfiguration.directory());
    validateStoreCompatibility(configuration, storeConfiguration);
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

  synchronized void start() throws IOException {
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

  synchronized boolean isReady() {
    return started && !closed && gateway.isReady();
  }

  synchronized InetSocketAddress listenAddress() {
    if (!started || closed) {
      throw new IllegalStateException("runtime is not running");
    }
    return gateway.listenAddress();
  }

  synchronized InetSocketAddress adminAddress() {
    if (!started || closed) {
      throw new IllegalStateException("runtime is not running");
    }
    return gateway.adminAddress();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;

    Throwable failure = null;
    try {
      gateway.close();
    } catch (RuntimeException | Error exception) {
      failure = exception;
    }

    try {
      store.close();
    } catch (IOException exception) {
      if (failure == null) {
        throw exception;
      }
      failure.addSuppressed(exception);
    }

    if (failure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (failure instanceof Error error) {
      throw error;
    }
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

  private static Path storePath(String directory) throws ConfigurationException {
    try {
      return Path.of(directory);
    } catch (InvalidPathException exception) {
      throw new ConfigurationException("configuration.store.directory is invalid", exception);
    }
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
      gateway.close();
    } catch (RuntimeException | Error closeFailure) {
      failure.addSuppressed(closeFailure);
    }
    try {
      store.close();
    } catch (IOException closeFailure) {
      failure.addSuppressed(closeFailure);
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
