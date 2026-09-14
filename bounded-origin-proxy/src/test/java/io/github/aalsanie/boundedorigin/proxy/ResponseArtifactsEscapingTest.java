package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseArtifactsEscapingTest {
  @Test
  void clientComputationEscapesEveryRemainingJsonControlClass() throws Exception {
    ClientComputation computation =
        new ClientComputation("\b\f\r\t\u0001x", "plain", Map.of("k", "\b\f\r\t\u0002x"));

    var artifact = ResponseArtifacts.clientComputation(computation);
    String json;

    try (var input = artifact.body().openStream()) {
      json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertEquals(
        "{\"type\":\"\\b\\f\\r\\t\\u0001x\","
            + "\"version\":\"plain\","
            + "\"parameters\":{\"k\":\"\\b\\f\\r\\t\\u0002x\"}}",
        json);
  }
}
