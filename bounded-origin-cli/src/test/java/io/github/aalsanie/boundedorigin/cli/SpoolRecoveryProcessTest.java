package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.cli.ZeroCodeEndToEndTest.RunningCli;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpoolRecoveryProcessTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void repeatedCrashesCannotAccumulateSpoolsBeyondTheConfiguredBudget(boolean incomplete)
      throws IOException, InterruptedException {
    int listen = CliTestSupport.freePort();
    int admin = distinctPort(listen);
    Path configuration = configuration(listen, admin, "store");
    for (int cycle = 0; cycle < 4; cycle++) {
      try (RunningCli cli = RunningCli.start(configuration, listen, admin, directory);
          Socket upload = new Socket()) {
        assertEquals(0, cli.metric("bounded_origin_spool_files"));
        assertEquals(0, cli.metric("bounded_origin_spool_bytes"));
        assertTrue(spools().isEmpty());
        upload.connect(new InetSocketAddress("127.0.0.1", listen), 2000);
        upload.setSoTimeout(5000);
        String head =
            "POST /denied/"
                + cycle
                + " HTTP/1.1\r\nHost: example.test\r\n"
                + "Content-Length: "
                + (incomplete ? 4096 : 2048)
                + "\r\nConnection: close\r\n\r\n";
        upload.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
        upload.getOutputStream().write(new byte[2048]);
        upload.getOutputStream().flush();
        if (incomplete) {
          cli.awaitMetricAtLeast("bounded_origin_spool_bytes", 2048, WAIT);
          awaitDiskBytes(2048);
          assertEquals(1, spools().size());
        } else {
          BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(upload.getInputStream(), StandardCharsets.US_ASCII));
          assertTrue(reader.readLine().startsWith("HTTP/1.1 403 "));
          assertTrue(reader.transferTo(Writer.nullWriter()) > 0);
          long deadline = System.nanoTime() + WAIT.toNanos();
          while (cli.metric("bounded_origin_spool_files") != 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
          }
          assertEquals(0, cli.metric("bounded_origin_spool_files"));
          assertTrue(spools().isEmpty());
        }
        cli.crash();
      }
      assertEquals(incomplete ? 1 : 0, spools().size());
      assertEquals(incomplete ? 2048 : 0, spoolBytes());
    }
  }

  @Test
  void competingProcessCannotRecoverTheLiveOwnersRequestBody()
      throws IOException, InterruptedException {
    int listen = CliTestSupport.freePort();
    int admin = distinctPort(listen);
    Path firstConfig = configuration(listen, admin, "first-store");
    try (RunningCli first = RunningCli.start(firstConfig, listen, admin, directory);
        Socket upload = new Socket()) {
      upload.connect(new InetSocketAddress("127.0.0.1", listen), 2000);
      upload
          .getOutputStream()
          .write(
              ("POST /one HTTP/1.1\r\nHost: example.test\r\n" + "Content-Length: 4096\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      upload.getOutputStream().write(new byte[2048]);
      upload.getOutputStream().flush();
      first.awaitMetricAtLeast("bounded_origin_spool_bytes", 2048, WAIT);
      awaitDiskBytes(2048);
      int secondListen = CliTestSupport.freePort();
      int secondAdmin = distinctPort(secondListen);
      Path secondConfig = configuration(secondListen, secondAdmin, "second-store");
      IOException failure =
          assertThrows(
              IOException.class,
              () -> {
                try (RunningCli second =
                    RunningCli.start(secondConfig, secondListen, secondAdmin, directory)) {
                  assertTrue(second.isAlive());
                }
              });
      assertTrue(failure.getMessage().contains("runtime failure"));
      assertEquals(1, spools().size());
      assertEquals(2048, spoolBytes());
      assertEquals(2048, first.metric("bounded_origin_spool_bytes"));
    }
  }

  private List<Path> spools() throws IOException {
    try (var files = Files.list(directory.resolve("spool"))) {
      return files.filter(path -> path.toString().endsWith(".tmp")).toList();
    }
  }

  private long spoolBytes() throws IOException {
    long bytes = 0;
    for (Path spool : spools()) {
      bytes += Files.size(spool);
    }
    return bytes;
  }

  private void awaitDiskBytes(long expected) throws IOException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (spoolBytes() != expected && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    assertEquals(expected, spoolBytes());
  }

  private static int distinctPort(int other) throws IOException {
    int port;
    do {
      port = CliTestSupport.freePort();
    } while (port == other);
    return port;
  }

  private Path configuration(int listen, int admin, String store) throws IOException {
    Path file = directory.resolve(store + ".yaml");
    Files.writeString(
        file,
        """
        schema: 1
        gateway:
          listen.host: 127.0.0.1
          listen.port: __LISTEN__
          admin.host: 127.0.0.1
          admin.port: __ADMIN__
          origin.host: 127.0.0.1
          origin.port: 1
          temporary.directory: '__SPOOL__'
          event-loop.threads: 1
          origin.event-loop.threads: 1
          http.max-request-body-bytes: 4096
          origin.max-result-bytes: 4096
          spool.max-bytes: 4096
          spool.max-files: 2
          origin.max-active: 1
          origin.max-queued: 0
        store:
          directory: '__STORE__'
          max-bytes: 65536
          max-artifact-bytes: 4096
        routes: []
        fallback:
          id: deny
          version: 1
          precedence: -2147483648
          strategy: DENY
        """
            .replace("__LISTEN__", Integer.toString(listen))
            .replace("__ADMIN__", Integer.toString(admin))
            .replace("__SPOOL__", path("spool"))
            .replace("__STORE__", path(store)));
    return file;
  }

  private String path(String child) {
    return directory.resolve(child).toString().replace('\\', '/').replace("'", "''");
  }
}
