package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseArtifactsTest {
  @Test
  void textResponseIsDeterministicAndNoStore() throws Exception {
    var artifact = ResponseArtifacts.text(503, "busy\n", Map.of("retry-after", "2"));
    assertEquals(503, artifact.statusCode());
    assertEquals("text/plain; charset=utf-8", artifact.metadata().get("content-type"));
    assertEquals("no-store", artifact.metadata().get("cache-control"));
    assertEquals("2", artifact.metadata().get("retry-after"));
    assertEquals(
        "busy\n", new String(artifact.body().openStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  @Test
  void clientComputeJsonIsSortedAndEscaped() throws Exception {
    ClientComputation computation =
        new ClientComputation("browser\"work", "v1", Map.of("z", "line\n", "a", "\\value"));
    var artifact = ResponseArtifacts.clientComputation(computation);
    String json = new String(artifact.body().openStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(200, artifact.statusCode());
    assertEquals(
        "{\"type\":\"browser\\\"work\",\"version\":\"v1\",\"parameters\":{\"a\":\"\\\\value\",\"z\":\"line\\n\"}}",
        json);
  }

  @Test
  void nullInputsAreRejected() {
    assertThrows(NullPointerException.class, () -> ResponseArtifacts.text(200, null));
    assertThrows(NullPointerException.class, () -> ResponseArtifacts.clientComputation(null));
  }
}
