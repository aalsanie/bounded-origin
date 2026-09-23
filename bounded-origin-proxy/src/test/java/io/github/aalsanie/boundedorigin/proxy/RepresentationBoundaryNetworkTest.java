package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepresentationBoundaryNetworkTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void responseTrailersCannotHidePrivacyConstraints(boolean persist) throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/public",
          (request, socket) -> {
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nCache-Control: public\r\nTransfer-Encoding: chunked\r\n\r\n6\r\nSECRET\r\n0\r\nCache-Control: private, no-store\r\n\r\n");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), directory);
      var store = new GatewayTestFixtures.MemoryArtifactStore();
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(config, engine(config, persist, Map.of()), store);
          RawHttpClient caller = new RawHttpClient(gateway.listenAddress())) {
        var response =
            caller.request("GET", "/public", Map.of("Host", "example.test"), new byte[0]);
        assertEquals(502, response.status());
        assertFalse(response.bodyText().contains("SECRET"));
        assertEquals(0, store.size());
        assertEquals(1, origin.requests());
        awaitMetric(gateway, "bounded_origin_origin_work_outstanding", 0);
        awaitMetric(gateway, "bounded_origin_spool_files", 0);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unselectedCallerInputsCannotReachTheSharedProducer(boolean persist) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<TestOriginServer.Request> received = new AtomicReference<>();
    try (TestOriginServer origin = new TestOriginServer()) {
      origin.respond(
          "/public",
          (request, socket) -> {
            received.set(request);
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            TestOriginServer.write(
                socket,
                "HTTP/1.1 200 OK\r\nCache-Control: public\r\nContent-Length: 6\r\n\r\npublic");
            return true;
          });
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), directory);
      var store = new GatewayTestFixtures.MemoryArtifactStore();
      try (BoundedOriginGateway gateway =
              GatewayTestFixtures.start(config, engine(config, persist, Map.of()), store);
          RawHttpClient first = new RawHttpClient(gateway.listenAddress());
          RawHttpClient second = new RawHttpClient(gateway.listenAddress())) {
        first.write(
            "GET /public?account=alice HTTP/1.1\r\nHost: example.test\r\nX-Account: alice\r\nAccept-Language: en\r\n\r\n");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        second.write(
            "GET /public?account=bob HTTP/1.1\r\nHost: example.test\r\nX-Account: bob\r\nAccept-Language: fr\r\n\r\n");
        awaitMetric(gateway, "bounded_origin_single_flight_joins_total", 1);
        assertEquals("/public", received.get().target());
        assertFalse(received.get().headers().containsKey("x-account"));
        assertFalse(received.get().headers().containsKey("accept-language"));
        release.countDown();
        assertEquals("public", first.readResponse("GET").bodyText());
        assertEquals("public", second.readResponse("GET").bodyText());
        assertEquals(1, origin.requests());
        assertEquals(persist ? 1 : 0, store.size());
      }
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void credentialsConditionsAndRangesCannotUseWarmArtifactsOrStartWork(boolean persist)
      throws Exception {
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), directory);
      var store = new GatewayTestFixtures.MemoryArtifactStore();
      store.defaultArtifact(
          new Artifact(
              200, 0, Map.of("cache-control", "public"), java.io.InputStream::nullInputStream));
      try (BoundedOriginGateway gateway =
          GatewayTestFixtures.start(config, engine(config, persist, Map.of()), store)) {
        for (String header :
            List.of(
                "Authorization",
                "Cookie",
                "Cookie2",
                "Proxy-Authorization",
                "Range",
                "If-Match",
                "If-None-Match",
                "If-Modified-Since",
                "If-Range",
                "Cache-Control",
                "Pragma")) {
          try (RawHttpClient caller = new RawHttpClient(gateway.listenAddress())) {
            assertEquals(
                403,
                caller
                    .request(
                        "GET",
                        "/public",
                        Map.of(
                            "Host",
                            "example.test",
                            header,
                            "synthetic-private-input",
                            "Connection",
                            header),
                        new byte[0])
                    .status(),
                header);
          }
        }
        assertEquals(0, origin.requests());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void conflictingOriginResponsesFailBeforeEitherCallerOrStoreSeesTheBody(boolean persist)
      throws Exception {
    List<String> responses =
        List.of(
            "Cache-Control: private, no-store\r\nVary: Authorization\r\n",
            "Cache-Control: public\r\nCache-Control: private\r\n",
            "Cache-Control: public, no-cache\r\n",
            "Cache-Control: public\r\nVary: *\r\n",
            "Cache-Control: public\r\nVary: X-Account\r\n",
            "Cache-Control: public\r\nSet-Cookie: session=alice\r\n",
            "Cache-Control: public\r\nExpires: Thu, 01 Jan 1970 00:00:00 GMT\r\n",
            "Cache-Control: public, max-age=1\r\n",
            "Cache-Control: public\r\nConnection: vary\r\nVary: *\r\n");
    for (int index = 0; index < responses.size(); index++) {
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      String headers = responses.get(index);
      try (TestOriginServer origin = new TestOriginServer()) {
        origin.respond(
            "/public",
            (request, socket) -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              TestOriginServer.write(
                  socket, "HTTP/1.1 200 OK\r\n" + headers + "Content-Length: 6\r\n\r\nSECRET");
              return true;
            });
        GatewayConfig config =
            GatewayTestFixtures.config(origin.port(), directory.resolve(Integer.toString(index)));
        var store = new GatewayTestFixtures.MemoryArtifactStore();
        try (BoundedOriginGateway gateway =
                GatewayTestFixtures.start(config, engine(config, persist, Map.of()), store);
            RawHttpClient leader = new RawHttpClient(gateway.listenAddress());
            RawHttpClient follower = new RawHttpClient(gateway.listenAddress())) {
          leader.write("GET /public HTTP/1.1\r\nHost: example.test\r\n\r\n");
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          follower.write("GET /public HTTP/1.1\r\nHost: example.test\r\n\r\n");
          awaitMetric(gateway, "bounded_origin_single_flight_joins_total", 1);
          release.countDown();
          for (RawHttpClient caller : List.of(leader, follower)) {
            var response = caller.readResponse("GET");
            assertEquals(502, response.status(), headers);
            assertFalse(response.bodyText().contains("SECRET"));
          }
          assertEquals(0, store.size());
          assertEquals(1, origin.requests());
          awaitMetric(gateway, "bounded_origin_origin_work_outstanding", 0);
          awaitMetric(gateway, "bounded_origin_spool_files", 0);
        }
      } finally {
        release.countDown();
      }
    }
  }

  private static PolicyEngine engine(
      GatewayConfig config, boolean persist, Map<String, List<String>> headers) {
    OriginPolicy policy =
        persist
            ? OriginPolicy.materialize(
                "public", 1, 100, "1", HttpOperation.canonicalizer(), config.globalBudget())
            : OriginPolicy.boundedCompute(
                "public", 1, 100, "1", HttpOperation.canonicalizer(), config.globalBudget());
    return new PolicyEngine(
        List.of(
            new PolicyRule(
                policy,
                request ->
                    Optional.of(
                        new HttpOperation(
                                "GET",
                                request.attributes().get("host").getFirst(),
                                "/public",
                                request.attributes().get("body-sha256").getFirst(),
                                request.trustLevel(),
                                RepresentationContract.PUBLIC_IMMUTABLE,
                                headers)
                            .operation()))),
        OriginPolicy.deny("fallback", 1, 0));
  }

  private static void awaitMetric(BoundedOriginGateway gateway, String name, long expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try (RawHttpClient admin = new RawHttpClient(gateway.adminAddress())) {
        String text =
            admin.request("GET", "/metrics", Map.of("Host", "admin"), new byte[0]).bodyText();
        if (text.lines().anyMatch(line -> line.equals(name + " " + expected))) {
          return;
        }
        assertTrue(System.nanoTime() < deadline, name + " did not reach " + expected + "\n" + text);
      }
      Thread.onSpinWait();
    }
  }
}
