package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedOriginCliTest {
  @TempDir Path temporaryDirectory;

  @Test
  void validatePerformsCrossSectionValidationWithoutCreatingRuntimeResources() throws IOException {
    Path valid = CliTestSupport.writeRuntimeConfiguration(temporaryDirectory, 0, 0);
    ByteArrayOutputStream validError = new ByteArrayOutputStream();

    int validExit =
        BoundedOriginCli.execute(
            new String[] {"validate", "--config", valid.toString()}, stream(validError));

    assertEquals(BoundedOriginCli.EXIT_SUCCESS, validExit);
    assertEquals("", validError.toString(StandardCharsets.UTF_8));
    assertFalse(Files.exists(temporaryDirectory.resolve("store")));
    assertFalse(Files.exists(temporaryDirectory.resolve("spool")));

    Path invalid =
        ConfigurationTestSupport.write(
            temporaryDirectory,
            CliTestSupport.runtimeYaml(temporaryDirectory, 0, 0)
                .replace("max-artifact-bytes: 262144", "max-artifact-bytes: 1"));
    ByteArrayOutputStream invalidError = new ByteArrayOutputStream();

    int invalidExit =
        BoundedOriginCli.execute(
            new String[] {"validate", "--config", invalid.toString()}, stream(invalidError));

    assertEquals(BoundedOriginCli.EXIT_CONFIGURATION, invalidExit);
    assertEquals("configuration rejected", invalidError.toString(StandardCharsets.UTF_8).trim());
    assertFalse(Files.exists(temporaryDirectory.resolve("store")));
  }

  @Test
  void rejectsMalformedCommandLinesWithStableUsageExitCode() {
    assertUsage(null);
    assertUsage(new String[0]);
    assertUsage(new String[] {"reload", "--config", "config.yaml"});
    assertUsage(new String[] {"validate", "--file", "config.yaml"});
    assertUsage(new String[] {"validate", "--config", null});
    assertUsage(new String[] {"validate", "--config", " "});
    assertUsage(new String[] {"validate", "--config", String.valueOf('\0')});
    assertUsage(new String[] {"validate", "--config", "config.yaml", "extra"});
  }

  @Test
  void distinguishesConfigurationReadAndValidationFailures() throws IOException {
    ByteArrayOutputStream missingError = new ByteArrayOutputStream();

    int missingExit =
        BoundedOriginCli.execute(
            new String[] {
              "validate", "--config", temporaryDirectory.resolve("missing.yaml").toString()
            },
            stream(missingError));

    assertEquals(BoundedOriginCli.EXIT_CONFIGURATION, missingExit);
    assertEquals(
        "configuration could not be read", missingError.toString(StandardCharsets.UTF_8).trim());

    Path invalid =
        ConfigurationTestSupport.write(
            temporaryDirectory,
            CliTestSupport.runtimeYaml(temporaryDirectory, 0, 0)
                .replace("strategy: DENY", "strategy: MATERIALIZE"));
    ByteArrayOutputStream invalidError = new ByteArrayOutputStream();

    int invalidExit =
        BoundedOriginCli.execute(
            new String[] {"validate", "--config", invalid.toString()}, stream(invalidError));

    assertEquals(BoundedOriginCli.EXIT_CONFIGURATION, invalidExit);
    assertEquals("configuration rejected", invalidError.toString(StandardCharsets.UTF_8).trim());
  }

  @Test
  void interruptedRunReturnsRuntimeFailureAndReleasesStore()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    int listenPort = CliTestSupport.freePort();
    Path configuration =
        CliTestSupport.writeRuntimeConfiguration(temporaryDirectory, listenPort, 0);
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    AtomicReference<Thread> worker = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      Future<Integer> result =
          executor.submit(
              () -> {
                worker.set(Thread.currentThread());
                int exit =
                    BoundedOriginCli.execute(
                        new String[] {"run", "--config", configuration.toString()}, stream(error));
                interrupted.set(Thread.currentThread().isInterrupted());
                return exit;
              });

      awaitListening(listenPort, result);
      worker.get().interrupt();

      assertEquals(BoundedOriginCli.EXIT_RUNTIME, result.get(10, TimeUnit.SECONDS));
      assertTrue(interrupted.get());
      assertEquals("runtime interrupted", error.toString(StandardCharsets.UTF_8).trim());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(temporaryDirectory.resolve("store"), 1_048_576, 262_144)) {
      assertNotNull(store);
      assertTrue(Files.isDirectory(temporaryDirectory.resolve("store")));
    }
  }

  private static void assertUsage(String[] arguments) {
    ByteArrayOutputStream error = new ByteArrayOutputStream();

    int exit = BoundedOriginCli.execute(arguments, stream(error));

    assertEquals(BoundedOriginCli.EXIT_USAGE, exit);
    assertEquals("usage error", error.toString(StandardCharsets.UTF_8).trim());
  }

  private static PrintStream stream(ByteArrayOutputStream output) {
    return new PrintStream(output, true, StandardCharsets.UTF_8);
  }

  private static void awaitListening(int port, Future<Integer> result) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (result.isDone()) {
        throw new AssertionError("CLI exited before listener became ready");
      }
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
        return;
      } catch (IOException exception) {
        Thread.sleep(25);
      }
    }
    throw new AssertionError("CLI listener did not become ready");
  }
}
