package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class ZeroCodeEndToEndTest {
  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @TempDir(cleanup = CleanupMode.NEVER)
  Path temporaryDirectory;

  @AfterEach
  void deleteTemporaryDirectory() throws IOException {
    IOException failure = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      try {
        deleteRecursively(temporaryDirectory);
        return;
      } catch (IOException exception) {
        failure = exception;
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
      }
    }
    throw new IOException("temporary directory remained locked", failure);
  }

  @Test
  void materializationSingleFlightAndSemanticAliasesUseOneOriginComputation()
      throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);

      try (RunningCli cli =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        origin.blockResponses();
        List<String> aliases = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
          aliases.add(
              index % 2 == 0
                  ? "/render/item?variant=%61&variant=b"
                  : "/render/item?variant=b&variant=a");
        }

        List<CompletableFuture<HttpResponse<String>>> concurrent =
            aliases.stream().map(target -> sendAsync(listenPort, target)).toList();

        cli.awaitMetricAtLeast("bounded_origin_single_flight_joins_total", 11, WAIT);
        assertTrue(origin.awaitRequestsAtLeast(1, WAIT));
        assertEquals(1, origin.requestCount());

        origin.releaseResponses();
        List<HttpResponse<String>> responses = join(concurrent);
        assertEquals(12, responses.size());
        assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200));
        assertEquals(1, new HashSet<>(responses.stream().map(HttpResponse::body).toList()).size());
        assertEquals(1, origin.requestCount());
        assertEquals(1, origin.maxActiveRequests());

        String materializedBody = responses.getFirst().body();
        HttpResponse<String> cached = get(listenPort, "/render/item?variant=a&variant=b");
        assertEquals(200, cached.statusCode());
        assertEquals(materializedBody, cached.body());
        assertEquals(1, origin.requestCount());

        HttpResponse<String> duplicateOne =
            get(listenPort, "/render/dup?variant=a&variant=b&variant=a");
        assertEquals(200, duplicateOne.statusCode());
        assertEquals(2, origin.requestCount());

        HttpResponse<String> duplicateAlias =
            get(listenPort, "/render/dup?variant=a&variant=a&variant=b");
        assertEquals(200, duplicateAlias.statusCode());
        assertEquals(duplicateOne.body(), duplicateAlias.body());
        assertEquals(2, origin.requestCount());

        assertEquals(200, get(listenPort, "/render/empty").statusCode());
        assertEquals(3, origin.requestCount());
        assertEquals(200, get(listenPort, "/render/empty?variant=").statusCode());
        assertEquals(4, origin.requestCount());

        assertEquals(200, get(listenPort, "/render/other?variant=a&variant=b").statusCode());
        assertEquals(5, origin.requestCount());
      }
    }
  }

  @Test
  void uniqueKeyPressureNeverExceedsConfiguredActiveAndQueueBudgets()
      throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);

      try (RunningCli cli =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        origin.blockResponses();
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
          requests.add(sendAsync(listenPort, "/render/load-" + index + "?variant=x"));
        }

        cli.awaitMetricAtLeast("bounded_origin_rejections_total", 4, WAIT);
        assertTrue(origin.awaitRequestsAtLeast(2, WAIT));
        assertEquals(2, cli.metric("bounded_origin_origin_active"));
        assertEquals(2, cli.metric("bounded_origin_origin_queue_depth"));
        assertEquals(2, origin.requestCount());
        assertTrue(origin.maxActiveRequests() <= 2);

        origin.releaseResponses();
        List<HttpResponse<String>> responses = join(requests);
        long successes =
            responses.stream().filter(response -> response.statusCode() == 200).count();
        long rejections =
            responses.stream().filter(response -> response.statusCode() == 503).count();

        assertEquals(4, successes);
        assertEquals(4, rejections);
        assertEquals(4, origin.requestCount());
        assertTrue(origin.maxActiveRequests() <= 2);
      }
    }
  }

  @Test
  void denyFallbackAndRoutePrecedenceAreEnforcedBeforeOrigin()
      throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);

      try (RunningCli cli =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        assertTrue(cli.isAlive());
        HttpResponse<String> denied = get(listenPort, "/unsafe/blocked");
        assertEquals(403, denied.statusCode());
        assertEquals(0, origin.requestCount());

        HttpResponse<String> fallback = get(listenPort, "/outside");
        assertEquals(403, fallback.statusCode());
        assertEquals(0, origin.requestCount());

        HttpResponse<String> allowed = get(listenPort, "/unsafe/allowed");
        assertEquals(200, allowed.statusCode());
        assertEquals(1, origin.requestCount());
        assertTrue(origin.targets().contains("/unsafe/allowed"));

        assertEquals(200, get(listenPort, "/health").statusCode());
        assertEquals(200, get(listenPort, "/health").statusCode());
        assertEquals(3, origin.requestCount());
      }
    }
  }

  @Test
  void artifactsSurviveRestartAndConfigurationChangesApplyOnlyAfterRestart()
      throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);
      String storedBody;

      try (RunningCli first =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        assertTrue(first.isAlive());
        HttpResponse<String> materialized = get(listenPort, "/render/persist?variant=a");
        assertEquals(200, materialized.statusCode());
        storedBody = materialized.body();
        assertEquals(1, origin.requestCount());

        ZeroCodeTestConfiguration.write(
            temporaryDirectory, listenPort, adminPort, origin.port(), true);

        assertEquals(200, get(listenPort, "/health").statusCode());
        assertEquals(2, origin.requestCount());
      }

      awaitPortAvailable(listenPort, WAIT);
      awaitPortAvailable(adminPort, WAIT);

      try (RunningCli second =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        assertTrue(second.isAlive());
        HttpResponse<String> persisted = get(listenPort, "/render/persist?variant=a");
        assertEquals(200, persisted.statusCode());
        assertEquals(storedBody, persisted.body());
        assertEquals(2, origin.requestCount());

        HttpResponse<String> changed = get(listenPort, "/health");
        assertEquals(403, changed.statusCode());
        assertEquals(2, origin.requestCount());
      }
    }
  }

  @Test
  void unselectedQueryCardinalityCannotCreateOriginWork() throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);

      try (RunningCli cli =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        origin.blockResponses();
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
          requests.add(
              sendAsync(
                  listenPort,
                  "/render/cardinality?variant=a&noise" + index + "=" + "x".repeat(128)));
        }

        cli.awaitMetricAtLeast("bounded_origin_single_flight_joins_total", 31, WAIT);
        assertTrue(origin.awaitRequestsAtLeast(1, WAIT));
        assertEquals(1, origin.requestCount());

        origin.releaseResponses();
        List<HttpResponse<String>> responses = join(requests);
        assertEquals(32, responses.size());
        assertTrue(responses.stream().allMatch(response -> response.statusCode() == 200));
        assertEquals(1, new HashSet<>(responses.stream().map(HttpResponse::body).toList()).size());
        assertEquals(1, origin.requestCount());
      }
    }
  }

  @Test
  void hostileHttpIsRejectedBeforeOriginWork() throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);

      try (RunningCli cli =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        assertTrue(cli.isAlive());
        List<String> requests =
            List.of(
                "GET /render/%2e%2e?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nConnection: close\r\n\r\n",
                "GET /render/a%2fb?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nConnection: close\r\n\r\n",
                "GET /render/a%5cb?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nConnection: close\r\n\r\n",
                "GET /render/%GG?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nConnection: close\r\n\r\n",
                "GET http://example.test/render/a?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nConnection: close\r\n\r\n",
                "GET /render/a?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nHost: attacker.test\r\n"
                    + "Connection: close\r\n\r\n",
                "GET /render/a?variant=a HTTP/1.1\r\n"
                    + "Host: example.test\r\nContent-Length: 0\r\n"
                    + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    + "0\r\n\r\n");

        for (String request : requests) {
          assertEquals(400, rawStatus(listenPort, request));
        }
        assertEquals(0, origin.requestCount());
      }
    }
  }

  @Test
  void corruptArtifactFailsClosedAndRecoveryRemainsSingleFlight()
      throws IOException, InterruptedException {
    try (SyntheticOrigin origin = new SyntheticOrigin()) {
      PortPair ports = freePorts();
      int listenPort = ports.listen();
      int adminPort = ports.admin();
      Path configuration =
          ZeroCodeTestConfiguration.write(
              temporaryDirectory, listenPort, adminPort, origin.port(), false);

      try (RunningCli cli =
          RunningCli.start(configuration, listenPort, adminPort, temporaryDirectory)) {
        HttpResponse<String> materialized = get(listenPort, "/render/corrupt?variant=a");
        assertEquals(200, materialized.statusCode());
        assertEquals(1, origin.requestCount());

        Path object = onlyRegularFile(temporaryDirectory.resolve("store").resolve("objects"));
        Files.write(object, new byte[] {0}, StandardOpenOption.TRUNCATE_EXISTING);

        HttpResponse<String> corruptRead = get(listenPort, "/render/corrupt?variant=a");
        assertEquals(500, corruptRead.statusCode());
        assertEquals(1, origin.requestCount());

        origin.blockResponses();
        List<CompletableFuture<HttpResponse<String>>> recovery = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
          recovery.add(sendAsync(listenPort, "/render/corrupt?variant=a"));
        }

        cli.awaitMetricAtLeast("bounded_origin_single_flight_joins_total", 15, WAIT);
        assertTrue(origin.awaitRequestsAtLeast(2, WAIT));
        assertEquals(2, origin.requestCount());

        origin.releaseResponses();
        List<HttpResponse<String>> recovered = join(recovery);
        assertEquals(16, recovered.size());
        assertTrue(recovered.stream().allMatch(response -> response.statusCode() == 200));
        assertEquals(1, new HashSet<>(recovered.stream().map(HttpResponse::body).toList()).size());
        assertEquals(2, origin.requestCount());
      }
    }
  }

  private static int rawStatus(int port, String request) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
      socket.setSoTimeout(5_000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();

      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
        String statusLine = reader.readLine();
        if (statusLine == null) {
          throw new IOException("gateway closed without an HTTP response");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
          throw new IOException("malformed HTTP status line: " + statusLine);
        }
        return Integer.parseInt(parts[1]);
      }
    }
  }

  private static Path onlyRegularFile(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      List<Path> files = paths.filter(Files::isRegularFile).toList();
      assertEquals(1, files.size());
      return files.getFirst();
    }
  }

  private static CompletableFuture<HttpResponse<String>> sendAsync(int port, String target) {
    return CLIENT.sendAsync(
        request(port, target), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> get(int port, String target)
      throws IOException, InterruptedException {
    return CLIENT.send(
        request(port, target), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpRequest request(int port, String target) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
        .timeout(WAIT)
        .GET()
        .build();
  }

  private static List<HttpResponse<String>> join(
      List<CompletableFuture<HttpResponse<String>>> futures) {
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .orTimeout(15, TimeUnit.SECONDS)
        .join();
    return futures.stream().map(CompletableFuture::join).toList();
  }

  private static PortPair freePorts() throws IOException {
    try (ServerSocket listen =
            new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        ServerSocket admin = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))) {
      return new PortPair(listen.getLocalPort(), admin.getLocalPort());
    }
  }

  private static void awaitPortAvailable(int port, Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      try (ServerSocket socket =
          new ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"))) {
        assertTrue(socket.isBound());
        return;
      } catch (IOException exception) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
      }
    }
    throw new IOException("port did not become available: " + port);
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path current, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.deleteIfExists(current);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private record PortPair(int listen, int admin) {}

  static final class RunningCli implements AutoCloseable {
    private final Process process;
    private final int adminPort;
    private final Path log;
    private final AtomicReference<IOException> logFailure = new AtomicReference<>();
    private final Thread logReader;
    private List<ProcessHandle> capturedDescendants = List.of();

    private RunningCli(Process process, int adminPort, Path log) {
      this.process = process;
      this.adminPort = adminPort;
      this.log = log;
      logReader = Thread.ofPlatform().daemon(true).start(this::copyOutput);
    }

    static RunningCli start(Path configuration, int listenPort, int adminPort, Path logDirectory)
        throws IOException, InterruptedException {
      Path launcherDirectory = launcherDirectory();
      List<String> command = new ArrayList<>();
      if (isWindows()) {
        command.add("cmd.exe");
        command.add("/d");
        command.add("/c");
        command.add(launcherDirectory.resolve("bounded-origin.bat").toString());
      } else {
        command.add(launcherDirectory.resolve("bounded-origin").toString());
      }
      command.add("run");
      command.add("--config");
      command.add(configuration.toString());

      Path log = Files.createTempFile(logDirectory, "bounded-origin-", ".log");
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      RunningCli running = new RunningCli(process, adminPort, log);
      try {
        running.awaitReady(WAIT);
        running.captureDescendants();
        assertTrue(canConnect(listenPort));
        return running;
      } catch (IOException | InterruptedException | RuntimeException | Error failure) {
        running.close();
        throw failure;
      }
    }

    boolean isAlive() {
      return process.isAlive();
    }

    private void copyOutput() {
      // The fixture owns the file handle; killed Windows descendants inherit only the pipe.
      try (OutputStream output = Files.newOutputStream(log)) {
        process.getInputStream().transferTo(output);
      } catch (IOException exception) {
        logFailure.set(exception);
      }
    }

    private void awaitOutputClosed() throws InterruptedException {
      assertTrue(logReader.join(Duration.ofSeconds(10)), "process output reader did not terminate");
      IOException failure = logFailure.get();
      if (failure != null) {
        throw new AssertionError("failed to capture packaged CLI output", failure);
      }
    }

    private void captureDescendants() {
      capturedDescendants = process.descendants().toList();
    }

    long metric(String name) throws IOException, InterruptedException {
      HttpResponse<String> response =
          CLIENT.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adminPort + "/metrics"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IOException("metrics endpoint returned " + response.statusCode());
      }
      for (String line : response.body().split("\\R")) {
        if (line.startsWith(name + " ")) {
          return Long.parseLong(line.substring(name.length() + 1).trim());
        }
      }
      throw new IOException("metric not found: " + name);
    }

    void awaitMetricAtLeast(String name, long expected, Duration timeout)
        throws IOException, InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        if (!process.isAlive()) {
          throw new IOException("bounded-origin exited early:\n" + logText());
        }
        if (metric(name) >= expected) {
          return;
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
      }
      throw new IOException("metric did not reach " + expected + ": " + name);
    }

    @Override
    public void close() {
      List<ProcessHandle> descendants = managedDescendants();
      try {
        if (!process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
          awaitOutputClosed();
        } else if (isWindows()) {
          crash();
        } else {
          process.destroy();
          if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
          }
          awaitDescendantsExit(descendants);
          awaitOutputClosed();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }

    void crash() throws InterruptedException {
      List<ProcessHandle> descendants = managedDescendants();
      // Stop the launcher first: after Java exits the Windows script can spawn an exit-code shell.
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      awaitDescendantsExit(descendants);
      awaitOutputClosed();
    }

    private List<ProcessHandle> managedDescendants() {
      List<ProcessHandle> descendants = new ArrayList<>(capturedDescendants);
      process
          .descendants()
          .filter(
              candidate ->
                  descendants.stream().noneMatch(existing -> existing.pid() == candidate.pid()))
          .forEach(descendants::add);
      return descendants;
    }

    private static void awaitDescendantsExit(List<ProcessHandle> descendants) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        List<ProcessHandle> alive = descendants.stream().filter(ProcessHandle::isAlive).toList();
        if (alive.isEmpty()) {
          return;
        }
        alive.forEach(ProcessHandle::destroyForcibly);
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
      }
      if (descendants.stream().anyMatch(ProcessHandle::isAlive)) {
        throw new AssertionError("bounded-origin descendant did not terminate");
      }
    }

    private void awaitReady(Duration timeout) throws IOException, InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        if (!process.isAlive()) {
          throw new IOException("bounded-origin exited before ready:\n" + logText());
        }
        try {
          HttpResponse<String> response =
              CLIENT.send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adminPort + "/ready"))
                      .timeout(Duration.ofSeconds(1))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
          if (response.statusCode() == 200) {
            return;
          }
        } catch (IOException exception) {
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
      }
      throw new IOException("bounded-origin did not become ready:\n" + logText());
    }

    private String logText() throws IOException {
      return Files.readString(log, StandardCharsets.UTF_8);
    }

    private static Path launcherDirectory() {
      String configured = System.getProperty("boundedOrigin.launcherDir");
      if (configured == null || configured.isBlank()) {
        return Path.of("build", "install", "bounded-origin", "bin").toAbsolutePath();
      }
      return Path.of(configured);
    }

    private static boolean isWindows() {
      return System.getProperty("os.name").startsWith("Windows");
    }

    private static boolean canConnect(int port) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
        return true;
      } catch (IOException exception) {
        return false;
      }
    }
  }
}
