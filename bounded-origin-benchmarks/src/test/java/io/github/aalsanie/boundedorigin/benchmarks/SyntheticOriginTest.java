package io.github.aalsanie.boundedorigin.benchmarks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

class SyntheticOriginTest {
  @TempDir Path directory;

  @Test
  void identityPreservesSelectedSemanticsAndIgnoresNoise() {
    String identity = SyntheticWork.identity(URI.create("/work/one?q=b&q=a"));
    assertEquals("/work/one|7:value:a|7:value:b", identity);
    for (String target : List.of("/work/%6Fne?x=1&q=%61&q=b&noise=2", "/work/one?q=a&q=b")) {
      assertEquals(identity, SyntheticWork.identity(URI.create(target)));
    }
    var distinct =
        List.of(
            "/work/one",
            "/work/two",
            "/work/one?q",
            "/work/one?q=",
            "/work/one?q=a",
            "/work/one?q=a&q=a",
            "/work/one?q=+",
            "/work/one?q=%20");
    assertEquals(
        distinct.size(),
        distinct.stream().map(URI::create).map(SyntheticWork::identity).distinct().count());
    assertEquals("/work/one", SyntheticWork.identity(URI.create("/work/one?")));
    assertArrayEquals(
        SyntheticWork.compute(identity, 5, 128), SyntheticWork.compute(identity, 5, 128));
    assertFalse(
        java.util.Arrays.equals(
            SyntheticWork.compute(identity, 4, 128), SyntheticWork.compute(identity, 5, 128)));
    assertEquals(128, SyntheticWork.compute(identity, 5, 128).length);
    assertEquals(
        "/work/one|7:value:a|7:value:b\nc123b7e76e596d92\n/work/one|7:value",
        new String(SyntheticWork.compute(identity, 5, 64), StandardCharsets.UTF_8));
    assertEquals(0, SyntheticWork.compute("", 0, 0).length);
  }

  @Test
  void independentJournalCountsOverlapAndCompletionBeforeResponse()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    Path events = directory.resolve("events.jsonl");
    try (SyntheticOrigin origin = new SyntheticOrigin(events, 1000, 128, true);
        HttpClient client = HttpClient.newHttpClient()) {
      var first =
          client.sendAsync(
              request(origin, "/work/one?q=a"), HttpResponse.BodyHandlers.ofByteArray());
      await(origin, "starts", 1);
      var second =
          client.sendAsync(
              request(origin, "/work/two?q=b"), HttpResponse.BodyHandlers.ofByteArray());
      await(origin, "starts", 2);
      assertEquals(2, number(origin.snapshot(), "active"));
      assertEquals(2, number(origin.snapshot(), "peak"));
      assertFalse(first.isDone());
      assertFalse(second.isDone());
      assertEquals(
          200,
          client
              .send(request(origin, "/control/stats"), HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertEquals(
          200,
          client
              .send(request(origin, "/control/release"), HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertArrayEquals(
          SyntheticWork.compute("/work/one|7:value:a", 1000, 128),
          first.get(10, TimeUnit.SECONDS).body());
      assertEquals(200, second.get(10, TimeUnit.SECONDS).statusCode());
      assertEquals(
          "public",
          second.get(10, TimeUnit.SECONDS).headers().firstValue("Cache-Control").orElseThrow());
      assertEquals(2, number(origin.snapshot(), "completed"));
      assertEquals(0, number(origin.snapshot(), "active"));
      assertTrue(number(origin.snapshot(), "work_cpu_ns") >= 0);
      assertEquals(
          500,
          client
              .send(request(origin, "/fail/one"), HttpResponse.BodyHandlers.discarding())
              .statusCode());
      assertEquals(3, number(origin.snapshot(), "completed"));
    }
    List<String> lines = Files.readAllLines(events);
    assertEquals(6, lines.size());
    assertEquals(1, number(lines.get(0), "active"));
    assertEquals(2, number(lines.get(1), "active"));
    assertEquals(
        "/work/one?q=a",
        new String(
            Base64.getDecoder().decode((String) decode(lines.get(0)).get("target_base64")),
            StandardCharsets.UTF_8));
    assertEquals("finish", decode(lines.get(5)).get("event"));
    assertEquals(0, number(lines.get(5), "active"));
  }

  @Test
  void transportResetAndCallerDisconnectDoNotEndActualWork()
      throws IOException, InterruptedException {
    try (SyntheticOrigin origin =
            new SyntheticOrigin(directory.resolve("reset.jsonl"), 100, 1024, true);
        HttpClient client = HttpClient.newHttpClient()) {
      try (Socket disconnected = new Socket("127.0.0.1", origin.port())) {
        disconnected
            .getOutputStream()
            .write(
                "GET /work/disconnected HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        await(origin, "starts", 1);
        disconnected.setSoLinger(true, 0);
      }
      // A raw socket prevents a library from transparently retrying the reset request.
      try (Socket reset = new Socket("127.0.0.1", origin.port())) {
        reset.setSoTimeout(10_000);
        reset
            .getOutputStream()
            .write(
                "GET /reset/one HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
        assertEquals(-1, reset.getInputStream().read());
      }
      await(origin, "starts", 2);
      assertEquals(2, number(origin.snapshot(), "active"));
      assertEquals(0, number(origin.snapshot(), "completed"));
      client.send(request(origin, "/control/release"), HttpResponse.BodyHandlers.discarding());
      await(origin, "completed", 2);
      await(origin, "write_failures", 1);
      assertEquals(0, number(origin.snapshot(), "active"));
    }
  }

  @Test
  void parametersAndExistingEvidenceAreRejectedAndMainWritesReadiness() throws IOException {
    for (long iterations : new long[] {-1, 1_000_000_001L}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SyntheticOrigin(directory.resolve("bad"), iterations, 64, false));
    }
    for (int bytes : new int[] {63, 16_777_217}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SyntheticOrigin(directory.resolve("bad"), 0, bytes, false));
    }
    assertThrows(IllegalArgumentException.class, () -> SyntheticOrigin.main(new String[0]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SyntheticOrigin.run(
                new String[] {"x", "1", "64", "invalid"}, new ByteArrayInputStream(new byte[0])));
    SyntheticOrigin.run(
        new String[] {directory.toString(), "0", "64", "free"},
        new ByteArrayInputStream(new byte[0]));
    assertTrue(number(Files.readString(directory.resolve("origin-ready.json")), "port") > 0);
    assertEquals(0, number(Files.readString(directory.resolve("origin-final.json")), "active"));
    assertThrows(IOException.class, () -> new OriginJournal(directory.resolve("origin.jsonl")));
  }

  @Test
  void closeReleasesControlledWorkAndWaitsForItsJournal() throws IOException {
    Path events = directory.resolve("close.jsonl");
    SyntheticOrigin origin = new SyntheticOrigin(events, 0, 64, true);
    try (Socket socket = new Socket("127.0.0.1", origin.port())) {
      socket
          .getOutputStream()
          .write(
              "GET /work/one HTTP/1.1\r\nHost: localhost\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      await(origin, "starts", 1);
    } finally {
      origin.close();
    }
    assertEquals(1, number(origin.snapshot(), "completed"));
    assertEquals(0, number(origin.snapshot(), "active"));
    assertEquals(2, Files.readAllLines(events).size());
  }

  private static HttpRequest request(SyntheticOrigin origin, String target) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + origin.port() + target))
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
  }

  private static void await(SyntheticOrigin origin, String field, long expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (number(origin.snapshot(), field) != expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, number(origin.snapshot(), field), origin.snapshot());
  }

  private static Map<?, ?> decode(String json) {
    return assertInstanceOf(
        Map.class, new Load(LoadSettings.builder().build()).loadFromString(json));
  }

  private static long number(String json, String field) {
    return assertInstanceOf(Number.class, decode(json).get(field)).longValue();
  }
}
