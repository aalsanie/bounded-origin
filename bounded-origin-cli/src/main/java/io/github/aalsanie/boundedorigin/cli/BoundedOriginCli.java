package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class BoundedOriginCli {
  static final int EXIT_SUCCESS = 0;
  static final int EXIT_CONFIGURATION = 2;
  static final int EXIT_RUNTIME = 3;
  static final int EXIT_USAGE = 64;

  private static final String RUN = "run";
  private static final String VALIDATE = "validate";
  private static final String CONFIG = "--config";

  private BoundedOriginCli() {}

  public static void main(String[] arguments) {
    System.exit(execute(arguments, System.err));
  }

  static int execute(String[] arguments, PrintStream error) {
    Objects.requireNonNull(error, "error");

    String command;
    Path configurationPath;
    try {
      command = parseCommand(arguments);
      configurationPath = parseConfigurationPath(arguments);
    } catch (IllegalArgumentException exception) {
      error.println("usage error");
      return EXIT_USAGE;
    }

    ConfigurationModel.RuntimeConfiguration configuration;
    try {
      configuration = new YamlConfigurationLoader().load(configurationPath);
    } catch (ConfigurationException exception) {
      error.println("configuration rejected");
      return EXIT_CONFIGURATION;
    } catch (IOException exception) {
      error.println("configuration could not be read");
      return EXIT_CONFIGURATION;
    }

    if (VALIDATE.equals(command)) {
      try {
        ConfiguredRuntime.validate(configuration);
        return EXIT_SUCCESS;
      } catch (ConfigurationException exception) {
        error.println("configuration rejected");
        return EXIT_CONFIGURATION;
      }
    }

    try {
      run(configuration, error);
      return EXIT_SUCCESS;
    } catch (ConfigurationException exception) {
      error.println("configuration rejected");
      return EXIT_CONFIGURATION;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      error.println("runtime interrupted");
      return EXIT_RUNTIME;
    } catch (IOException | RuntimeException exception) {
      error.println("runtime failure");
      return EXIT_RUNTIME;
    }
  }

  private static String parseCommand(String[] arguments) {
    if (arguments == null || arguments.length != 3) {
      throw new IllegalArgumentException("expected command, --config, and path");
    }
    String command = arguments[0];
    if (!RUN.equals(command) && !VALIDATE.equals(command)) {
      throw new IllegalArgumentException("unsupported command");
    }
    if (!CONFIG.equals(arguments[1])) {
      throw new IllegalArgumentException("expected --config");
    }
    if (arguments[2] == null || arguments[2].isBlank()) {
      throw new IllegalArgumentException("configuration path must not be blank");
    }
    return command;
  }

  private static Path parseConfigurationPath(String[] arguments) {
    return Path.of(arguments[2]);
  }

  private static void run(
      ConfigurationModel.RuntimeConfiguration configuration, PrintStream error)
      throws ConfigurationException, IOException, InterruptedException {
    try (ConfiguredRuntime runtime = ConfiguredRuntime.assemble(configuration)) {
      Thread shutdownHook =
          new Thread(() -> closeDuringShutdown(runtime, error), "bounded-origin-shutdown");
      Runtime jvm = Runtime.getRuntime();
      jvm.addShutdownHook(shutdownHook);
      try {
        runtime.start();
        new CountDownLatch(1).await();
      } finally {
        jvm.removeShutdownHook(shutdownHook);
      }
    }
  }

  static void closeDuringShutdown(ConfiguredRuntime runtime, PrintStream error) {
    try {
      runtime.close();
    } catch (IOException exception) {
      error.println("runtime shutdown failure");
    }
  }
}
