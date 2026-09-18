package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class BoundedOriginCliProcessTest {
  private static final long PROCESS_TIMEOUT_SECONDS = 20;

  @TempDir Path temporaryDirectory;

  @Test
  void generatedNativeLauncherValidatesConfiguration() throws IOException, InterruptedException {
    Path launcherDirectory = launcherDirectory();
    assertTrue(Files.isRegularFile(launcherDirectory.resolve("bounded-origin")));
    assertTrue(Files.isRegularFile(launcherDirectory.resolve("bounded-origin.bat")));

    Path configuration = CliTestSupport.writeRuntimeConfiguration(temporaryDirectory, 0, 0);
    ProcessResult result = runToCompletion("validate", "--config", configuration.toString());

    assertEquals(BoundedOriginCli.EXIT_SUCCESS, result.exitCode());
    assertEquals("", result.output());
  }

  @Test
  void malformedCommandLineUsesStableUsageExitCode() throws IOException, InterruptedException {
    ProcessResult result = runToCompletion("reload", "--config", "ignored.yaml");

    assertEquals(BoundedOriginCli.EXIT_USAGE, result.exitCode());
    assertEquals("usage error", result.output());
  }

  @Test
  void invalidConfigurationFailsBeforePublicBind() throws IOException, InterruptedException {
    try (ServerSocket blocker = CliTestSupport.bindLoopback(0)) {
      Path configuration =
          ConfigurationTestSupport.write(
              temporaryDirectory,
              CliTestSupport.runtimeYaml(temporaryDirectory, blocker.getLocalPort(), 0)
                  .replace("strategy: DENY", "strategy: MATERIALIZE"));

      ProcessResult result = runToCompletion("run", "--config", configuration.toString());

      assertEquals(BoundedOriginCli.EXIT_CONFIGURATION, result.exitCode());
      assertEquals("configuration rejected", result.output());
    }
  }

  @Test
  void validateDoesNotBindConfiguredListeners() throws IOException, InterruptedException {
    try (ServerSocket publicBlocker = CliTestSupport.bindLoopback(0);
        ServerSocket adminBlocker = CliTestSupport.bindLoopback(0)) {
      Path configuration =
          CliTestSupport.writeRuntimeConfiguration(
              temporaryDirectory, publicBlocker.getLocalPort(), adminBlocker.getLocalPort());

      ProcessResult result = runToCompletion("validate", "--config", configuration.toString());

      assertEquals(BoundedOriginCli.EXIT_SUCCESS, result.exitCode());
      assertEquals("", result.output());
    }
  }

  @Test
  void lockedStoreFailsBeforeServing() throws IOException, InterruptedException {
    int listenPort = CliTestSupport.freePort();
    Path configuration =
        CliTestSupport.writeRuntimeConfiguration(temporaryDirectory, listenPort, 0);

    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(temporaryDirectory.resolve("store"), 1_048_576, 262_144)) {
      assertNotNull(store);
      Process process = startProcess("run", "--config", configuration.toString());
      boolean acceptedConnection = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TIMEOUT_SECONDS);
      while (process.isAlive() && System.nanoTime() < deadline) {
        if (canConnect(listenPort)) {
          acceptedConnection = true;
          break;
        }
        Thread.sleep(25);
      }

      assertTrue(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      String output = output(process);
      assertFalse(acceptedConnection);
      assertEquals(BoundedOriginCli.EXIT_RUNTIME, process.exitValue());
      assertEquals("runtime failure", output);
    }

    try (ServerSocket rebound = CliTestSupport.bindLoopback(listenPort)) {
      assertEquals(listenPort, rebound.getLocalPort());
    }
  }

  @Test
  void startupFailureReleasesStoreLock() throws IOException, InterruptedException {
    try (ServerSocket blocker = CliTestSupport.bindLoopback(0)) {
      Path configuration =
          CliTestSupport.writeRuntimeConfiguration(temporaryDirectory, blocker.getLocalPort(), 0);

      ProcessResult result = runToCompletion("run", "--config", configuration.toString());

      assertEquals(BoundedOriginCli.EXIT_RUNTIME, result.exitCode());
      assertEquals("runtime failure", result.output());
    }

    assertStoreAvailable();
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void gracefulTerminationReleasesResourcesAndRestartRereadsConfiguration()
      throws IOException, InterruptedException {
    int firstPort = CliTestSupport.freePort();
    int secondPort = CliTestSupport.freePort();
    Path configuration = CliTestSupport.writeRuntimeConfiguration(temporaryDirectory, firstPort, 0);

    Process first = startProcess("run", "--config", configuration.toString());
    awaitListening(first, firstPort);
    first.destroy();
    assertTrue(first.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertStoreAvailable();
    assertFalse(canConnect(firstPort));

    ConfigurationTestSupport.write(
        temporaryDirectory, CliTestSupport.runtimeYaml(temporaryDirectory, secondPort, 0));

    Process second = startProcess("run", "--config", configuration.toString());
    awaitListening(second, secondPort);
    assertFalse(canConnect(firstPort));
    second.destroy();
    assertTrue(second.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertStoreAvailable();
  }

  private void assertStoreAvailable() throws IOException {
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(temporaryDirectory.resolve("store"), 1_048_576, 262_144)) {
      assertNotNull(store);
      assertTrue(Files.isDirectory(temporaryDirectory.resolve("store")));
    }
  }

  private static void awaitListening(Process process, int port)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_TIMEOUT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new AssertionError("CLI exited before listener became ready: " + output(process));
      }
      if (canConnect(port)) {
        return;
      }
      Thread.sleep(25);
    }
    process.destroyForcibly();
    throw new AssertionError("CLI listener did not become ready");
  }

  private static boolean canConnect(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
      return true;
    } catch (IOException exception) {
      return false;
    }
  }

  private static ProcessResult runToCompletion(String... arguments)
      throws IOException, InterruptedException {
    Process process = startProcess(arguments);
    if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("CLI process did not terminate");
    }
    return new ProcessResult(process.exitValue(), output(process));
  }

  private static Process startProcess(String... arguments) throws IOException {
    List<String> command = new ArrayList<>();
    Path directory = launcherDirectory();
    if (isWindows()) {
      command.add("cmd.exe");
      command.add("/d");
      command.add("/c");
      command.add(directory.resolve("bounded-origin.bat").toString());
    } else {
      command.add(directory.resolve("bounded-origin").toString());
    }
    command.addAll(List.of(arguments));
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }

  private static Path launcherDirectory() {
    String configured = System.getProperty("boundedOrigin.launcherDir");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    return Path.of("build", "install", "bounded-origin", "bin").toAbsolutePath();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").startsWith("Windows");
  }

  private static String output(Process process) throws IOException {
    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
  }

  private record ProcessResult(int exitCode, String output) {}
}
