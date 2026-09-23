package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.cli.ZeroCodeEndToEndTest.RunningCli;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OriginOwnershipProcessTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .version(HttpClient.Version.HTTP_1_1)
          .build();

  @TempDir Path directory;

  @Test
  void aSecondProcessCannotAcquireTheSameComputationOwnershipDomain()
      throws IOException, InterruptedException {
    try (DisconnectInsensitiveOrigin origin = new DisconnectInsensitiveOrigin(false)) {
      int listen = CliTestSupport.freePort();
      int admin = distinctPort(listen);
      Path config = configuration(origin.port(), listen, admin, "BOUNDED_COMPUTE", "late");
      try (RunningCli first = RunningCli.start(config, listen, admin, directory)) {
        int secondListen = CliTestSupport.freePort();
        int secondAdmin = distinctPort(secondListen);
        Path competing = directory.resolve("competing.yaml");
        Files.writeString(
            competing,
            Files.readString(config)
                .replace("listen.port: " + listen, "listen.port: " + secondListen)
                .replace("admin.port: " + admin, "admin.port: " + secondAdmin)
                .replace(path("store"), path("other-store"))
                .replace(path("spool"), path("other-spool")));
        IOException failure =
            assertThrows(
                IOException.class,
                () -> {
                  try (RunningCli second =
                      RunningCli.start(competing, secondListen, secondAdmin, directory)) {
                    assertTrue(second.isAlive());
                  }
                });
        assertTrue(failure.getMessage().contains("runtime failure"));
        assertEquals(1, first.metric("bounded_origin_origin_work_registry_available"));
        origin.release();
        assertEquals(200, get(listen, "/work/one"));
        assertEquals(1, origin.executions());
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "BOUNDED_COMPUTE,response", "MATERIALIZE,response",
    "BOUNDED_COMPUTE,executor", "MATERIALIZE,executor",
    "BOUNDED_COMPUTE,reset", "MATERIALIZE,reset",
    "BOUNDED_COMPUTE,disconnect", "MATERIALIZE,disconnect",
    "BOUNDED_COMPUTE,crash", "MATERIALIZE,crash"
  })
  void uncertainComputationCannotBeReplacedByDistinctKeysOrForgottenOnRestart(
      String strategy, String failure)
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    try (DisconnectInsensitiveOrigin origin =
        new DisconnectInsensitiveOrigin(failure.equals("reset"))) {
      int listen = CliTestSupport.freePort();
      int admin = distinctPort(listen);
      Path config = configuration(origin.port(), listen, admin, strategy, failure);
      try (RunningCli cli = RunningCli.start(config, listen, admin, directory)) {
        if (failure.equals("disconnect") || failure.equals("crash")) {
          try (Socket socket = startRequest(listen, "/work/one")) {
            assertTrue(origin.awaitEntered());
            assertTrue(socket.isConnected());
            assertEquals(1, origin.active());
          }
          if (failure.equals("crash")) {
            cli.crash();
          } else {
            cli.awaitMetricAtLeast("bounded_origin_origin_work_unresolved", 1, WAIT);
          }
        } else {
          var first =
              CLIENT.sendAsync(request(listen, "/work/one"), HttpResponse.BodyHandlers.ofString());
          assertTrue(origin.awaitEntered());
          assertEquals(
              failure.equals("reset") ? 502 : 504,
              first.get(WAIT.toSeconds(), TimeUnit.SECONDS).statusCode());
        }
        if (!failure.equals("crash")) {
          cli.awaitMetricAtLeast("bounded_origin_origin_work_unresolved", 1, WAIT);
          assertBlocked(cli, listen, origin);
        }
      }
      try (RunningCli restarted = RunningCli.start(config, listen, admin, directory)) {
        assertBlocked(restarted, listen, origin);
        assertEquals(0, restarted.metric("bounded_origin_origin_active"));
      }
      assertEquals(1, origin.active());
      assertEquals(1, origin.maximum());
      assertEquals(1, origin.executions());
      assertTrue(origin.iterations() > 0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"BOUNDED_COMPUTE", "MATERIALIZE"})
  void workCanFinishAfterAllCallersDisconnectAndMaterializationSurvivesRestart(String strategy)
      throws IOException, InterruptedException {
    try (DisconnectInsensitiveOrigin origin = new DisconnectInsensitiveOrigin(false)) {
      int listen = CliTestSupport.freePort();
      int admin = distinctPort(listen);
      Path config = configuration(origin.port(), listen, admin, strategy, "late");
      try (RunningCli cli = RunningCli.start(config, listen, admin, directory)) {
        try (Socket first = startRequest(listen, "/work/one")) {
          assertTrue(origin.awaitEntered());
          try (Socket follower = startRequest(listen, "/work/one")) {
            cli.awaitMetricAtLeast("bounded_origin_single_flight_joins_total", 1, WAIT);
            assertTrue(first.isConnected());
            assertTrue(follower.isConnected());
          }
        }
        assertEquals(1, origin.active());
        origin.release();
        awaitMetric(cli, "bounded_origin_origin_in_flight", 0);
        awaitMetric(cli, "bounded_origin_spool_files", 0);
        assertEquals(0, cli.metric("bounded_origin_origin_work_outstanding"));
        assertEquals(0, origin.active());
        assertEquals(1, origin.executions());
      }
      try (RunningCli restarted = RunningCli.start(config, listen, admin, directory)) {
        assertTrue(restarted.isAlive());
        assertEquals(200, get(listen, "/work/one"));
        assertEquals(strategy.equals("MATERIALIZE") ? 1 : 2, origin.executions());
        assertEquals(1, origin.maximum());
      }
    }
  }

  private static void assertBlocked(RunningCli cli, int listen, DisconnectInsensitiveOrigin origin)
      throws IOException, InterruptedException {
    for (String key : new String[] {"two", "three", "four", "one"}) {
      assertEquals(503, get(listen, "/work/" + key));
      assertEquals(1, origin.active());
      assertEquals(1, origin.maximum());
      assertEquals(1, origin.executions());
    }
    assertEquals(1, cli.metric("bounded_origin_origin_work_outstanding"));
    assertEquals(1, cli.metric("bounded_origin_origin_work_unresolved"));
  }

  private static void awaitMetric(RunningCli cli, String name, long value)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (cli.metric(name) != value && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(value, cli.metric(name));
  }

  private Path configuration(int origin, int listen, int admin, String strategy, String failure)
      throws IOException {
    String response =
        failure.equals("executor") || failure.equals("crash") || failure.equals("late")
            ? "PT10S"
            : "PT1S";
    String execution = failure.equals("executor") ? "PT1S" : "PT10S";
    String yaml =
        """
        schema: 1
        gateway:
          listen.host: 127.0.0.1
          listen.port: %d
          admin.host: 127.0.0.1
          admin.port: %d
          origin.host: 127.0.0.1
          origin.port: %d
          origin.completion-contract: RESPONSE_COMPLETE
          origin.ownership-directory: '%s'
          temporary.directory: '%s'
          origin.max-active: 1
          origin.max-queued: 0
          origin.max-connections: 1
          origin.max-pending-acquires: 0
          origin.max-execution-duration: %s
          origin.response-timeout: %s
          origin.max-result-bytes: 4096
          request.timeout: PT15S
          drain.timeout: PT1S
        store:
          directory: '%s'
          max-bytes: 1048576
          max-artifact-bytes: 4096
        routes:
          - id: work
            version: 1
            precedence: 1
            match:
              path: /work/{id}
              method: GET
            strategy: %s
            representation: PUBLIC_IMMUTABLE
            materializer-version: v1
            key:
              path: [id]
            budget:
              max-active: 1
              max-queued: 0
              max-execution-duration: %s
              max-result-bytes: 4096
        fallback:
          id: deny
          version: 1
          precedence: -2147483648
          strategy: DENY
        """
            .replace("\n", "%n")
            .formatted(
                listen,
                admin,
                origin,
                path("ownership"),
                path("spool"),
                execution,
                response,
                path("store"),
                strategy,
                execution);
    Path config = directory.resolve("ownership.yaml");
    Files.writeString(config, yaml);
    return config;
  }

  private String path(String name) {
    return directory.resolve(name).toAbsolutePath().toString().replace("'", "''");
  }

  private static HttpRequest request(int port, String target) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target))
        .timeout(WAIT)
        .GET()
        .build();
  }

  private static int get(int port, String target) throws IOException, InterruptedException {
    return CLIENT.send(request(port, target), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static Socket startRequest(int port, String target) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
    socket
        .getOutputStream()
        .write(
            ("GET "
                    + target
                    + " HTTP/1.1\r\nHost: 127.0.0.1:"
                    + port
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    socket.getOutputStream().flush();
    return socket;
  }

  private static int distinctPort(int first) throws IOException {
    int candidate;
    do {
      candidate = CliTestSupport.freePort();
    } while (candidate == first);
    return candidate;
  }
}
