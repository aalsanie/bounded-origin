package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayNetworkTest {
  @TempDir Path temporaryDirectory;

  @Test
  void streamsChunkedUploadsUsesKeepAliveAndChunksLargeResponses() throws Exception {
    AtomicReference<TestOriginServer.Request> echoed = new AtomicReference<>();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/echo",
          (request, socket) -> {
            echoed.set(request);
            byte[] body = request.body();
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: "
                    + body.length
                    + "\r\nConnection: keep-alive\r\n\r\n");
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            return true;
          });
      String large = "x".repeat(128);
      origin.fixed("/large", 200, large);

      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        client.write(
            "POST /echo HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: keep-alive\r\n\r\n"
                + "3\r\nabc\r\n"
                + "3\r\ndef\r\n"
                + "0\r\n\r\n");
        RawHttpClient.Response echoedResponse = client.readResponse("POST");
        assertEquals(200, echoedResponse.status());
        assertEquals("abcdef", echoedResponse.bodyText());

        RawHttpClient.Response largeResponse =
            client.request(
                "GET",
                "/large",
                Map.of("Host", "example.test", "Connection", "keep-alive"),
                new byte[0]);
        assertEquals(200, largeResponse.status());
        assertEquals(large, largeResponse.bodyText());
        assertEquals("chunked", largeResponse.header("transfer-encoding"));
        assertEquals(null, largeResponse.header("content-length"));
      }

      TestOriginServer.Request forwarded = echoed.get();
      assertNotNull(forwarded);
      assertArrayEquals("abcdef".getBytes(StandardCharsets.UTF_8), forwarded.body());
      assertEquals("6", forwarded.headers().get("content-length"));
      assertFalse(forwarded.headers().containsKey("transfer-encoding"));
      assertEquals(1, origin.connections());
      assertEquals(2, origin.requests());
    }
  }

  @Test
  void rejectsAmbiguousMalformedAndOversizedRequestsBeforeOrigin() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.of(
                  "http.max-request-body-bytes", "16",
                  "spool.max-bytes", "1048576"));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertEquals(
            400,
            raw(
                    gateway,
                    "POST / HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Content-Length: 1\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "0\r\n\r\n",
                    "POST")
                .status());
        assertEquals(
            400,
            raw(
                    gateway,
                    "GET http://example.test/x HTTP/1.1\r\n" + "Host: example.test\r\n\r\n",
                    "GET")
                .status());
        assertEquals(
            400,
            raw(gateway, "GET /a/%2e%2e/b HTTP/1.1\r\n" + "Host: example.test\r\n\r\n", "GET")
                .status());
        assertEquals(
            400,
            raw(
                    gateway,
                    "GET / HTTP/1.1\r\n" + "Host: example.test\r\n" + "Host: attacker.test\r\n\r\n",
                    "GET")
                .status());
        assertEquals(
            413,
            raw(
                    gateway,
                    "POST / HTTP/1.1\r\n" + "Host: example.test\r\n" + "Content-Length: 17\r\n\r\n",
                    "POST")
                .status());
        assertEquals(
            413,
            raw(
                    gateway,
                    "POST / HTTP/1.1\r\n"
                        + "Host: example.test\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n"
                        + "11\r\n12345678901234567\r\n0\r\n\r\n",
                    "POST")
                .status());
        assertEquals(
            400,
            raw(
                    gateway,
                    "GET / HTTP/1.1\r\nHost: example.test\r\nX-Large: "
                        + "x".repeat(9_000)
                        + "\r\n\r\n",
                    "GET")
                .status());
      }
      assertEquals(0, origin.requests());
    }
  }

  @Test
  void stripsHopByHopPrivilegedAndUntrustedForwardingHeaders() throws Exception {
    AtomicReference<TestOriginServer.Request> captured = new AtomicReference<>();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/headers",
          (request, socket) -> {
            captured.set(request);
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\n");
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        RawHttpClient.Response response =
            client.request(
                "GET",
                "/headers",
                Map.ofEntries(
                    Map.entry("Host", "example.test"),
                    Map.entry("Connection", "X-Remove"),
                    Map.entry("X-Remove", "secret"),
                    Map.entry("Forwarded", "for=attacker"),
                    Map.entry("X-Forwarded-For", "attacker"),
                    Map.entry("X-Bounded-Origin-Trusted", "yes"),
                    Map.entry("X-App", "kept")),
                new byte[0]);
        assertEquals(200, response.status());
      }

      Map<String, String> headers = captured.get().headers();
      assertEquals("kept", headers.get("x-app"));
      assertEquals("example.test", headers.get("host"));
      assertEquals("0", headers.get("content-length"));
      assertFalse(headers.containsKey("x-remove"));
      assertFalse(headers.containsKey("forwarded"));
      assertFalse(headers.containsKey("x-forwarded-for"));
      assertFalse(headers.containsKey("x-bounded-origin-trusted"));
    }
  }

  @Test
  void expectContinueIsAcknowledgedBeforeBodyIsRead() throws Exception {
    AtomicReference<TestOriginServer.Request> captured = new AtomicReference<>();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/continue",
          (request, socket) -> {
            captured.set(request);
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        client.write(
            "POST /continue HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Content-Length: 3\r\n"
                + "Expect: 100-continue\r\n\r\n");
        RawHttpClient.Response interim = client.readResponse("POST");
        assertEquals(100, interim.status());
        client.write("abc");
        RawHttpClient.Response response = client.readResponse("POST");
        assertEquals(200, response.status());
        assertEquals("ok", response.bodyText());
      }
      assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), captured.get().body());
    }
  }

  @Test
  void sameKeySingleFlightSurvivesRequesterDisconnect() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/same",
          (request, socket) -> {
            entered.countDown();
            if (!TestOriginServer.await(release, Duration.ofSeconds(5))) {
              throw new IOException("test release timed out");
            }
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: keep-alive\r\n\r\nshared");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-active", "1"),
                  Map.entry("origin.max-queued", "0"),
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "0"),
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S"),
                  Map.entry("drain.timeout", "PT5S")));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient second = new RawHttpClient(gateway.listenAddress());
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        RawHttpClient first = new RawHttpClient(gateway.listenAddress());
        try {
          Future<RawHttpClient.Response> firstResult =
              executor.submit(
                  () -> first.request("GET", "/same", Map.of("Host", "example.test"), new byte[0]));
          assertTrue(entered.await(2, TimeUnit.SECONDS));
          Future<RawHttpClient.Response> secondResult =
              executor.submit(
                  () ->
                      second.request("GET", "/same", Map.of("Host", "example.test"), new byte[0]));

          awaitMetric(
              gateway, "bounded_origin_single_flight_joins_total", 1, Duration.ofSeconds(2));
          first.close();
          release.countDown();

          RawHttpClient.Response response = secondResult.get(3, TimeUnit.SECONDS);
          assertEquals(200, response.status());
          assertEquals("shared", response.bodyText());
          assertEquals(1, origin.requests());
          awaitTrackedFlights(gateway, 0, Duration.ofSeconds(2));
          assertThrows(ExecutionException.class, () -> firstResult.get(2, TimeUnit.SECONDS));
        } finally {
          first.close();
        }
      }
    }
  }

  @Test
  void uniqueWorkOverloadReturns503AndRetryAfter() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/a",
          (request, socket) -> {
            entered.countDown();
            assertTrue(TestOriginServer.await(release, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\na");
            return true;
          });
      origin.fixed("/b", 200, "b");
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-active", "1"),
                  Map.entry("origin.max-queued", "0"),
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "0"),
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S")));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<RawHttpClient.Response> active =
            executor.submit(() -> request(gateway, "GET", "/a"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        RawHttpClient.Response rejected = request(gateway, "GET", "/b");
        assertEquals(503, rejected.status());
        assertEquals("1", rejected.header("retry-after"));
        assertEquals(1, origin.requests());

        release.countDown();
        assertEquals(200, active.get(3, TimeUnit.SECONDS).status());
      }
    }
  }

  @Test
  void originAcquireTimeoutReturns504WithoutUnboundingPendingWork() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/a",
          (request, socket) -> {
            entered.countDown();
            assertTrue(TestOriginServer.await(release, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\na");
            return true;
          });
      origin.fixed("/b", 200, "b");
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-active", "2"),
                  Map.entry("origin.max-queued", "0"),
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "1"),
                  Map.entry("origin.acquire-timeout", "PT0.1S"),
                  Map.entry("origin.max-execution-duration", "PT3S"),
                  Map.entry("origin.response-timeout", "PT3S"),
                  Map.entry("request.timeout", "PT4S")));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<RawHttpClient.Response> active =
            executor.submit(() -> request(gateway, "GET", "/a"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        RawHttpClient.Response timedOut = request(gateway, "GET", "/b");
        assertEquals(504, timedOut.status());
        assertEquals(null, timedOut.header("retry-after"));
        assertEquals(1, origin.requests());

        release.countDown();
        assertEquals(200, active.get(3, TimeUnit.SECONDS).status());
      }
    }
  }

  @Test
  void originPoolSaturationIsBoundedAndReturns503() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/a",
          (request, socket) -> {
            entered.countDown();
            assertTrue(TestOriginServer.await(release, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: keep-alive\r\n\r\na");
            return true;
          });
      origin.fixed("/b", 200, "b");
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-active", "2"),
                  Map.entry("origin.max-queued", "0"),
                  Map.entry("origin.max-connections", "1"),
                  Map.entry("origin.max-pending-acquires", "0"),
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S")));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<RawHttpClient.Response> active =
            executor.submit(() -> request(gateway, "GET", "/a"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        RawHttpClient.Response rejected = request(gateway, "GET", "/b");
        assertEquals(503, rejected.status());
        assertEquals("1", rejected.header("retry-after"));
        assertEquals(1, origin.requests());

        release.countDown();
        assertEquals(200, active.get(3, TimeUnit.SECONDS).status());
      }
    }
  }

  @Test
  void materializedArtifactReplaysAfterGatewayRestartWithoutOrigin() throws Exception {
    GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
    int originPort;
    GatewayConfig config;
    try (TestOriginServer origin = new TestOriginServer()) {
      originPort = origin.port();
      origin.fixed("/materialized", 200, "persisted");
      config = GatewayTestFixtures.config(originPort, temporaryDirectory);
      OriginPolicy policy = GatewayTestFixtures.materializePolicy(config.globalBudget());
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(config, GatewayTestFixtures.engine(policy), store)) {
        RawHttpClient.Response first = request(gateway, "GET", "/materialized");
        assertEquals(200, first.status());
        assertEquals("persisted", first.bodyText());
        assertEquals(1, origin.requests());
        assertEquals(1, store.size());
      }
    }

    OriginPolicy policy = GatewayTestFixtures.materializePolicy(config.globalBudget());
    try (BoundedOriginGateway restarted =
        GatewayTestFixtures.start(config, GatewayTestFixtures.engine(policy), store)) {
      RawHttpClient.Response replay = request(restarted, "GET", "/materialized");
      assertEquals(200, replay.status());
      assertEquals("persisted", replay.bodyText());
    }
    assertTrue(originPort > 0);
  }

  @Test
  void originFailuresAreContainedAndInformationalResponsesRemainValid() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/partial",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nabc");
            return false;
          });
      origin.respond(
          "/dead",
          (request, socket) -> {
            Thread.sleep(1_000);
            return false;
          });
      origin.respond(
          "/large",
          (request, socket) -> {
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      origin.respond(
          "/reset",
          (request, socket) -> {
            socket.setSoLinger(true, 0);
            return false;
          });
      origin.respond(
          "/hints",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n"
                    + "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      origin.respond(
          "/close-delimited",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/plain\r\n\r\nclose");
            return false;
          });

      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-result-bytes", "32"),
                  Map.entry("origin.response-timeout", "PT0.2S"),
                  Map.entry("origin.max-execution-duration", "PT1S"),
                  Map.entry("request.timeout", "PT2S")));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        assertEquals(502, request(gateway, "GET", "/partial").status());
        assertEquals(504, request(gateway, "GET", "/dead").status());
        int afterDead = origin.requests();
        RawHttpClient.Response cooldown = request(gateway, "GET", "/dead");
        assertEquals(503, cooldown.status());
        assertEquals("1", cooldown.header("retry-after"));
        assertEquals(afterDead, origin.requests());
        RawHttpClient.Response oversized = request(gateway, "GET", "/large");
        assertEquals(502, oversized.status());
        assertTrue(oversized.bodyText().contains("exceeds configured limit"));
        assertEquals(502, request(gateway, "GET", "/reset").status());

        RawHttpClient.Response hints = request(gateway, "GET", "/hints");
        assertEquals(200, hints.status());
        assertEquals("ok", hints.bodyText());

        RawHttpClient.Response closeDelimited = request(gateway, "GET", "/close-delimited");
        assertEquals(200, closeDelimited.status());
        assertEquals("close", closeDelimited.bodyText());
      }
    }
  }

  @Test
  void headAndNotModifiedPreserveRepresentationLengthWithoutBodies() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/head",
          (request, socket) -> {
            assertEquals("HEAD", request.method());
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 123\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      origin.respond(
          "/not-modified",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 304 Not Modified\r\nContent-Length: 456\r\nConnection: keep-alive\r\n\r\n");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore())) {
        RawHttpClient.Response head = request(gateway, "HEAD", "/head");
        assertEquals(200, head.status());
        assertEquals("123", head.header("content-length"));
        assertEquals(0, head.body().length);

        RawHttpClient.Response notModified = request(gateway, "GET", "/not-modified");
        assertEquals(304, notModified.status());
        assertEquals("456", notModified.header("content-length"));
        assertEquals(0, notModified.body().length);
      }
    }
  }

  @Test
  void slowUploadTimesOutBeforeOriginExecution() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/upload", 200, "unexpected");
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("request.timeout", "PT0.2S"),
                  Map.entry("origin.max-execution-duration", "PT0.1S"),
                  Map.entry("origin.response-timeout", "PT0.1S"),
                  Map.entry("idle.timeout", "PT1S")));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        client.write(
            "POST /upload HTTP/1.1\r\n"
                + "Host: example.test\r\n"
                + "Content-Length: 10\r\n\r\n"
                + "x");
        RawHttpClient.Response timeout = client.readResponse("POST");
        assertEquals(408, timeout.status());
        assertTrue(client.awaitClosed(Duration.ofSeconds(1)));
      }
      assertEquals(0, origin.requests());
    }
  }

  @Test
  void idleKeepAliveConnectionIsClosed() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/idle", 200, "ok");
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(), temporaryDirectory, Map.of("idle.timeout", "PT0.15S"));
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(
                  config,
                  GatewayTestFixtures.engine(
                      GatewayTestFixtures.boundedPolicy(config.globalBudget())),
                  new GatewayTestFixtures.MemoryArtifactStore());
          RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        RawHttpClient.Response response =
            client.request(
                "GET",
                "/idle",
                Map.of("Host", "example.test", "Connection", "keep-alive"),
                new byte[0]);
        assertEquals(200, response.status());
        assertTrue(client.awaitClosed(Duration.ofSeconds(2)));
      }
    }
  }

  @Test
  void adminReportsDrainAndGracefulShutdownWaitsForActiveRequest() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/drain",
          (request, socket) -> {
            entered.countDown();
            assertTrue(TestOriginServer.await(release, Duration.ofSeconds(5)));
            TestOriginServer.write(
                socket, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok");
            return true;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S"),
                  Map.entry("drain.timeout", "PT4S")));
      BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore());
      try (gateway;
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<RawHttpClient.Response> request =
            executor.submit(() -> request(gateway, "GET", "/drain"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        InetSocketAddressHolder admin = new InetSocketAddressHolder(gateway.adminAddress());

        Future<?> closing = executor.submit(gateway::close);
        awaitReadyStatus(admin.address(), 503, Duration.ofSeconds(2));
        assertEquals(200, admin(admin.address(), "/health").status());
        String metrics = admin(admin.address(), "/metrics").bodyText();
        assertTrue(metric(metrics, "bounded_origin_active_requests") >= 1);
        assertFalse(closing.isDone());

        release.countDown();
        assertEquals(200, request.get(3, TimeUnit.SECONDS).status());
        closing.get(3, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void drainTimeoutForcesBlockedConnectionsClosed() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/blocked",
          (request, socket) -> {
            entered.countDown();
            TestOriginServer.await(release, Duration.ofSeconds(5));
            return false;
          });
      GatewayConfig config =
          GatewayTestFixtures.config(
              origin.port(),
              temporaryDirectory,
              Map.ofEntries(
                  Map.entry("origin.max-execution-duration", "PT4S"),
                  Map.entry("origin.response-timeout", "PT4S"),
                  Map.entry("request.timeout", "PT5S"),
                  Map.entry("drain.timeout", "PT0.15S")));
      BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.boundedPolicy(config.globalBudget())),
              new GatewayTestFixtures.MemoryArtifactStore());
      try (gateway;
          ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<RawHttpClient.Response> blocked =
            executor.submit(() -> request(gateway, "GET", "/blocked"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        long started = System.nanoTime();
        gateway.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMillis < 3_000, "forced drain took too long: " + elapsedMillis + "ms");
        assertThrows(ExecutionException.class, () -> blocked.get(2, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void adminListenerRejectsUnsupportedAndMalformedRequests() throws Exception {
    int originPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config = GatewayTestFixtures.config(originPort, temporaryDirectory);
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.clientComputePolicy()),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      assertEquals(
          405,
          rawAdmin(
                  gateway,
                  "POST /health HTTP/1.1\r\nHost: admin\r\nContent-Length: 0\r\n\r\n",
                  "POST")
              .status());
      assertEquals(404, admin(gateway.adminAddress(), "/missing").status());
      assertEquals(
          400,
          rawAdmin(
                  gateway,
                  "GET /health HTTP/1.1\r\nHost: admin\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                  "GET")
              .status());
      assertEquals(400, rawAdmin(gateway, "GET /health HTTP/1.1\r\n\r\n", "GET").status());
    }
  }

  @Test
  void clientComputeArtifactOnlyAndDenyNeverReachOrigin() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      GatewayTestFixtures.MemoryArtifactStore store = new GatewayTestFixtures.MemoryArtifactStore();
      store.defaultArtifact(GatewayTestFixtures.artifact(200, "cached"));
      Map<String, OriginPolicy> policies =
          Map.of(
              "/artifact",
              GatewayTestFixtures.artifactOnlyPolicy(),
              "/client",
              GatewayTestFixtures.clientComputePolicy(),
              "/deny",
              OriginPolicy.deny("deny", 1, 100));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(config, GatewayTestFixtures.routeEngine(policies), store)) {
        RawHttpClient.Response artifact = request(gateway, "GET", "/artifact");
        assertEquals(200, artifact.status());
        assertEquals("cached", artifact.bodyText());

        RawHttpClient.Response client = request(gateway, "GET", "/client");
        assertEquals(200, client.status());
        assertTrue(client.bodyText().contains("\"type\":\"sha256\""));

        RawHttpClient.Response denied = request(gateway, "GET", "/deny");
        assertEquals(403, denied.status());
        assertEquals(403, request(gateway, "GET", "/unknown").status());

        store.clearDefaultArtifact();
        RawHttpClient.Response missing = request(gateway, "GET", "/artifact?variant=miss");
        assertEquals(404, missing.status());
      }
      assertEquals(0, origin.requests());
    }
  }

  @Test
  void ambiguousAndFailingPoliciesFailClosed() throws Exception {
    int originPort = GatewayTestFixtures.unusedPort();
    GatewayConfig config = GatewayTestFixtures.config(originPort, temporaryDirectory);
    OriginPolicy first =
        OriginPolicy.clientCompute(
            "first",
            1,
            100,
            "v1",
            Canonicalizers.byDimensions("path"),
            new io.github.aalsanie.boundedorigin.api.ClientComputation("a", "1", Map.of()));
    OriginPolicy second =
        OriginPolicy.clientCompute(
            "second",
            1,
            100,
            "v1",
            Canonicalizers.byDimensions("path"),
            new io.github.aalsanie.boundedorigin.api.ClientComputation("b", "1", Map.of()));
    java.util.function.Function<io.github.aalsanie.boundedorigin.api.RequestDescriptor, Operation>
        operation =
            descriptor ->
                new Operation("http.request", Map.of("path", descriptor.attributes().get("path")));
    PolicyEngine ambiguous =
        new PolicyEngine(
            List.of(
                new PolicyRule(first, request -> Optional.of(operation.apply(request))),
                new PolicyRule(second, request -> Optional.of(operation.apply(request)))),
            OriginPolicy.deny("fallback", 1, Integer.MIN_VALUE));
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config, ambiguous, new GatewayTestFixtures.MemoryArtifactStore())) {
      assertEquals(500, request(gateway, "GET", "/ambiguous").status());
    }

    PolicyEngine failing =
        new PolicyEngine(
            List.of(
                new PolicyRule(
                    first,
                    request -> {
                      throw new IllegalStateException("policy failure");
                    })),
            OriginPolicy.deny("fallback", 1, Integer.MIN_VALUE));
    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(config, failing, new GatewayTestFixtures.MemoryArtifactStore())) {
      assertEquals(500, request(gateway, "GET", "/policy-error").status());
    }
  }

  @Test
  void artifactStoreFailuresAreGatewayFailures() throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.fixed("/write", 200, "value");
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), temporaryDirectory);
      GatewayTestFixtures.MemoryArtifactStore readFailure =
          new GatewayTestFixtures.MemoryArtifactStore();
      readFailure.failReads(true);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
              readFailure)) {
        assertEquals(500, request(gateway, "GET", "/read").status());
      }

      GatewayTestFixtures.MemoryArtifactStore writeFailure =
          new GatewayTestFixtures.MemoryArtifactStore();
      writeFailure.failWrites(true);
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(
              config,
              GatewayTestFixtures.engine(
                  GatewayTestFixtures.materializePolicy(config.globalBudget())),
              writeFailure)) {
        RawHttpClient.Response response = request(gateway, "GET", "/write");
        assertEquals(500, response.status());
        assertTrue(response.bodyText().contains("artifact store unavailable"));
      }
    }
  }

  private static RawHttpClient.Response request(
      BoundedOriginGateway gateway, String method, String path) throws IOException {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      return client.request(method, path, Map.of("Host", "example.test"), new byte[0]);
    }
  }

  private static RawHttpClient.Response raw(
      BoundedOriginGateway gateway, String request, String method) throws IOException {
    try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
      client.write(request);
      return client.readResponse(method);
    }
  }

  private static RawHttpClient.Response rawAdmin(
      BoundedOriginGateway gateway, String request, String method) throws IOException {
    try (RawHttpClient client = new RawHttpClient(gateway.adminAddress())) {
      client.write(request);
      return client.readResponse(method);
    }
  }

  private static RawHttpClient.Response admin(java.net.InetSocketAddress address, String path)
      throws IOException {
    try (RawHttpClient client = new RawHttpClient(address)) {
      return client.request("GET", path, Map.of("Host", "admin"), new byte[0]);
    }
  }

  private static void awaitReadyStatus(
      java.net.InetSocketAddress address, int status, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    Throwable last = null;
    while (System.nanoTime() - deadline < 0) {
      try {
        if (admin(address, "/ready").status() == status) {
          return;
        }
      } catch (IOException exception) {
        last = exception;
      }
      Thread.sleep(10);
    }
    AssertionError failure = new AssertionError("ready endpoint did not reach status " + status);
    if (last != null) {
      failure.initCause(last);
    }
    throw failure;
  }

  private static void awaitMetric(
      BoundedOriginGateway gateway, String name, long expectedMinimum, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadline < 0) {
      String body = admin(gateway.adminAddress(), "/metrics").bodyText();
      if (metric(body, name) >= expectedMinimum) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("metric did not reach expected value: " + name);
  }

  private static long metric(String metrics, String name) {
    for (String line : metrics.split("\\R")) {
      if (line.startsWith(name + " ")) {
        return (long) Double.parseDouble(line.substring(name.length() + 1));
      }
    }
    throw new AssertionError("metric not found: " + name);
  }

  private static void awaitTrackedFlights(
      BoundedOriginGateway gateway, int expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadline < 0) {
      if (gateway.trackedFlights() == expected) {
        return;
      }
      Thread.sleep(10);
    }
    assertEquals(expected, gateway.trackedFlights());
  }

  private record InetSocketAddressHolder(java.net.InetSocketAddress address) {}
}
