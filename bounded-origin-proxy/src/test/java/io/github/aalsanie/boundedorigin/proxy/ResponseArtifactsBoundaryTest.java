package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseArtifactsBoundaryTest {
  @Test
  void clientComputationEscapesEveryJsonControlSequence() throws Exception {
    ClientComputation computation =
        new ClientComputation(
            "\"\\\b\f\n\r\t\u0001Z", "v\u001f", Map.of("k\n", "\"\\\b\f\n\r\t\u0000Z"));

    var artifact = ResponseArtifacts.clientComputation(computation);
    String json = new String(artifact.body().openStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(
        "{\"type\":\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001Z\","
            + "\"version\":\"v\\u001f\","
            + "\"parameters\":{\"k\\n\":\"\\\"\\\\\\b\\f\\n\\r\\t\\u0000Z\"}}",
        json);
    assertEquals(json.getBytes(StandardCharsets.UTF_8).length, artifact.contentLength());
  }

  @Test
  void textResponseRejectsNullExtraHeaders() {
    assertThrows(NullPointerException.class, () -> ResponseArtifacts.text(200, "ok", null));
  }

  @Test
  void extraHeadersMayDeliberatelyOverrideDefaults() {
    var artifact =
        ResponseArtifacts.text(
            200, "ok", Map.of("content-type", "application/custom", "cache-control", "max-age=1"));

    assertEquals("application/custom", artifact.metadata().get("content-type"));
    assertEquals("max-age=1", artifact.metadata().get("cache-control"));
  }
}
