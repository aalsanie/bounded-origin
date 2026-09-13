package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseArtifactsJsonTest {
  @Test
  void clientComputationEscapesEveryJsonControlForm() throws Exception {
    String controls = "\"\\\b\f\n\r\t\u0001x";
    ClientComputation computation =
        new ClientComputation(controls, controls, Map.of(controls, controls));

    String json =
        new String(
            ResponseArtifacts.clientComputation(computation).body().openStream().readAllBytes(),
            StandardCharsets.UTF_8);

    String escaped = "\\\"\\\\\\b\\f\\n\\r\\t\\u0001x";
    assertEquals(
        "{\"type\":\""
            + escaped
            + "\",\"version\":\""
            + escaped
            + "\",\"parameters\":{\""
            + escaped
            + "\":\""
            + escaped
            + "\"}}",
        json);
  }

  @Test
  void emptyClientParametersProduceAnEmptyObject() throws Exception {
    ClientComputation computation = new ClientComputation("browser", "v1", Map.of());

    String json =
        new String(
            ResponseArtifacts.clientComputation(computation).body().openStream().readAllBytes(),
            StandardCharsets.UTF_8);

    assertEquals("{\"type\":\"browser\",\"version\":\"v1\",\"parameters\":{}}", json);
  }

  @Test
  void explicitTextHeadersMayOverrideDefaultMetadata() {
    var artifact =
        ResponseArtifacts.text(
            429,
            "later\n",
            Map.of("content-type", "application/problem+json", "cache-control", "private"));

    assertEquals("application/problem+json", artifact.metadata().get("content-type"));
    assertEquals("private", artifact.metadata().get("cache-control"));
  }
}
