package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestValueTypesTest {
  @TempDir Path temporaryDirectory;

  @Test
  void gatewayRequestDefensivelyCopiesHeaders() throws Exception {
    StreamingSpool.Result body = body("x");
    var validated =
        new HttpRequestSecurity.ValidatedRequest("POST", "/x", "/x", "", "host", 1, false);
    ArrayList<String> values = new ArrayList<>(List.of("one"));
    GatewayRequest request = new GatewayRequest(validated, Map.of("x", values), body);
    values.add("two");

    assertEquals(List.of("one"), request.originHeaders().get("x"));
    assertThrows(
        UnsupportedOperationException.class, () -> request.originHeaders().put("y", List.of("z")));
    body.close();
  }

  @Test
  void gatewayRequestRejectsInvalidValues() throws Exception {
    StreamingSpool.Result body = body("");
    var validated = new HttpRequestSecurity.ValidatedRequest("GET", "/", "/", "", "host", 0, false);
    assertThrows(NullPointerException.class, () -> new GatewayRequest(null, Map.of(), body));
    assertThrows(NullPointerException.class, () -> new GatewayRequest(validated, null, body));
    assertThrows(
        NullPointerException.class,
        () ->
            new GatewayRequest(
                validated, java.util.Collections.singletonMap(null, List.of("x")), body));
    assertThrows(
        NullPointerException.class,
        () -> new GatewayRequest(validated, java.util.Collections.singletonMap("x", null), body));
    assertThrows(NullPointerException.class, () -> new GatewayRequest(validated, Map.of(), null));
    body.close();
  }

  @Test
  void originRequestValidatesAndDefensivelyCopiesHeaders() throws Exception {
    StreamingSpool.Result body = body("abc");
    ArrayList<String> values = new ArrayList<>(List.of("one"));
    OriginRequest request = new OriginRequest("POST", "/x", Map.of("x", values), body, 10);
    values.add("two");

    assertEquals(List.of("one"), request.headers().get("x"));
    assertEquals(3, request.bodyLength());
    assertThrows(
        IllegalArgumentException.class, () -> new OriginRequest(" ", "/", Map.of(), body, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new OriginRequest("GET", " ", Map.of(), body, 1));
    assertThrows(NullPointerException.class, () -> new OriginRequest("GET", "/", null, body, 1));
    assertThrows(
        NullPointerException.class,
        () ->
            new OriginRequest(
                "GET", "/", java.util.Collections.singletonMap(null, List.of("x")), body, 1));
    assertThrows(
        NullPointerException.class,
        () ->
            new OriginRequest("GET", "/", java.util.Collections.singletonMap("x", null), body, 1));
    assertThrows(
        NullPointerException.class, () -> new OriginRequest("GET", "/", Map.of(), null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new OriginRequest("GET", "/", Map.of(), body, 0));
    body.close();
  }

  @Test
  void temporaryArtifactBodyRejectsReadsAfterDeletion() throws Exception {
    StreamingSpool.Result result = body("abc");
    TemporaryArtifactBody body = new TemporaryArtifactBody(result);
    try (java.io.InputStream input = body.openStream()) {
      assertEquals("abc", new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    body.delete();
    body.delete();
    assertEquals(true, body.deleted());
    assertThrows(java.io.IOException.class, body::openStream);
  }

  private StreamingSpool.Result body(String value) throws Exception {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    Path path = Files.createTempFile(temporaryDirectory, "body-", ".tmp");
    Files.write(path, bytes);
    SpoolQuota quota = new SpoolQuota(Math.max(1, bytes.length + 1), 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    reservation.reserve(bytes.length);
    return new StreamingSpool.Result(path, bytes.length, "digest", reservation);
  }
}
