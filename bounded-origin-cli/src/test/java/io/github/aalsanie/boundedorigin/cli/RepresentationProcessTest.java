package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.aalsanie.boundedorigin.cli.ZeroCodeEndToEndTest.RunningCli;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepresentationProcessTest {
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(3))
          .build();
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void canonicalPublicVariantsRemainIsolatedAcrossCallersAndRestart(boolean persist)
      throws IOException, InterruptedException {
    try (Origin origin = new Origin(false)) {
      int listen = CliTestSupport.freePort();
      int admin = distinctPort(listen);
      Path configuration = configuration(origin, listen, admin, persist);
      try (RunningCli cli = RunningCli.start(configuration, listen, admin, directory)) {
        assertTrue(cli.isAlive());
        var english =
            request(
                listen,
                "/render/%41?noise=alice&variant=%7e&variant=",
                Map.of("Accept-Language", "en", "X-Account", "alice"));
        assertEquals(200, english.statusCode());
        assertEquals("/render/A?variant=&variant=~|[en]|anonymous", english.body());
        var french =
            request(
                listen,
                "/render/A?variant=&noise=bob&variant=~",
                Map.of("Accept-Language", "fr", "X-Account", "bob"));
        assertEquals(200, french.statusCode());
        assertEquals("/render/A?variant=&variant=~|[fr]|anonymous", french.body());
        assertEquals(2, origin.requests.size());
        assertEquals(
            english.body(),
            request(
                    listen,
                    "/render/A?variant=~&variant=&noise=third",
                    Map.of("Accept-Language", "en"))
                .body());
        assertEquals(persist ? 2 : 3, origin.requests.size());
        for (String credential : List.of("Authorization", "Cookie", "Proxy-Authorization")) {
          assertEquals(403, credentialRequest(listen, credential), credential);
        }
        assertEquals(persist ? 2 : 3, origin.requests.size());
      }
      int beforeRestart = origin.requests.size();
      try (RunningCli cli = RunningCli.start(configuration, listen, admin, directory)) {
        assertTrue(cli.isAlive());
        assertEquals(
            "/render/A?variant=&variant=~|[en]|anonymous",
            request(
                    listen,
                    "/render/%41?variant=%7e&variant=&noise=after-restart",
                    Map.of("Accept-Language", "en"))
                .body());
        assertEquals(beforeRestart + (persist ? 0 : 1), origin.requests.size());
        assertEquals(persist ? 1 : 0, cli.metric("bounded_origin_artifact_hits_total"));
      }
      assertTrue(
          origin.requests.stream()
              .allMatch(value -> value.endsWith("|anonymous") && !value.contains("noise")));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void privateOriginOutputIsRejectedBeforeLiveSharingAndCannotSurviveAsAnArtifact(boolean persist)
      throws IOException,
          InterruptedException,
          java.util.concurrent.ExecutionException,
          java.util.concurrent.TimeoutException {
    try (Origin origin = new Origin(true)) {
      int listen = CliTestSupport.freePort();
      int admin = distinctPort(listen);
      Path configuration = configuration(origin, listen, admin, persist);
      try (RunningCli cli = RunningCli.start(configuration, listen, admin, directory)) {
        var leader =
            CLIENT.sendAsync(
                httpRequest(listen, "/render/private", Map.of("X-Account", "alice")),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(origin.entered.await(5, TimeUnit.SECONDS));
        var follower =
            CLIENT.sendAsync(
                httpRequest(listen, "/render/private?noise=bob", Map.of("X-Account", "bob")),
                HttpResponse.BodyHandlers.ofString());
        cli.awaitMetricAtLeast("bounded_origin_single_flight_joins_total", 1, WAIT);
        origin.release.countDown();
        for (var response :
            List.of(leader.get(5, TimeUnit.SECONDS), follower.get(5, TimeUnit.SECONDS))) {
          assertEquals(502, response.statusCode());
          assertFalse(response.body().contains("SECRET"));
        }
        assertEquals(1, origin.requests.size());
        assertEquals(0, cli.metric("bounded_origin_artifact_hits_total"));
        assertEquals(0, cli.metric("bounded_origin_origin_work_outstanding"));
      }
      try (RunningCli cli = RunningCli.start(configuration, listen, admin, directory)) {
        assertEquals(502, request(listen, "/render/private", Map.of()).statusCode());
        assertEquals(2, origin.requests.size());
        assertEquals(0, cli.metric("bounded_origin_artifact_hits_total"));
        assertEquals(0, cli.metric("bounded_origin_origin_work_unresolved"));
      }
    }
  }

  private Path configuration(Origin origin, int listen, int admin, boolean persist)
      throws IOException {
    Path configuration =
        ZeroCodeTestConfiguration.write(
            directory, listen, admin, origin.server.getAddress().getPort(), false);
    String yaml =
        Files.readString(configuration)
            .replace("path: [id]", "path: [id]\n      headers: [accept-language]");
    if (!persist) {
      yaml =
          yaml.replace("strategy: MATERIALIZE", "strategy: BOUNDED_COMPUTE")
              .replace("representation: PUBLIC_IMMUTABLE", "representation: PUBLIC");
    }
    Files.writeString(configuration, yaml);
    return configuration;
  }

  private static HttpResponse<String> request(int port, String target, Map<String, String> headers)
      throws IOException, InterruptedException {
    return CLIENT.send(
        httpRequest(port, target, headers),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static int credentialRequest(int port, String credential) throws IOException {
    // The JDK HTTP client removes Proxy-Authorization when no proxy is configured.
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET /render/A?variant=~&variant= HTTP/1.1\r\nHost: 127.0.0.1:"
                      + port
                      + "\r\nAccept-Language: en\r\n"
                      + credential
                      + ": synthetic-secret\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
      String line = reader.readLine();
      if (line == null) {
        throw new IOException("gateway returned no status line");
      }
      return Integer.parseInt(line.split(" ")[1]);
    }
  }

  private static HttpRequest httpRequest(int port, String target, Map<String, String> headers) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target)).timeout(WAIT).GET();
    headers.forEach(request::header);
    return request.build();
  }

  private static int distinctPort(int listen) throws IOException {
    int port;
    do {
      port = CliTestSupport.freePort();
    } while (port == listen);
    return port;
  }

  private static final class Origin implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release;
    private final boolean privateOutput;

    Origin(boolean privateOutput) throws IOException {
      this.privateOutput = privateOutput;
      release = new CountDownLatch(privateOutput ? 1 : 0);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::handle);
      server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String representation =
            exchange.getRequestURI()
                + "|"
                + exchange.getRequestHeaders().getOrDefault("Accept-Language", List.of())
                + "|"
                + exchange
                    .getRequestHeaders()
                    .getOrDefault("X-Account", List.of("anonymous"))
                    .getFirst();
        requests.add(representation);
        entered.countDown();
        try {
          if (!release.await(15, TimeUnit.SECONDS)) {
            throw new IOException("origin response phase was not released");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("origin interrupted", exception);
        }
        byte[] bytes = (privateOutput ? "SECRET" : representation).getBytes(StandardCharsets.UTF_8);
        exchange
            .getResponseHeaders()
            .set("Cache-Control", privateOutput ? "private, no-store" : "public");
        exchange.getResponseHeaders().set("Vary", "Accept-Language");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    }

    @Override
    public void close() {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
