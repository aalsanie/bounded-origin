package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactTest {
  @Test
  void preservesStatusStreamingBodyAndCopiesMetadata() throws IOException {
    byte[] bytes = {1, 2, 3};
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("type", "binary");
    Artifact artifact =
        new Artifact(201, bytes.length, metadata, () -> new ByteArrayInputStream(bytes));
    metadata.put("type", "changed");

    assertEquals(201, artifact.statusCode());
    assertEquals(3, artifact.contentLength());
    assertEquals(Map.of("type", "binary"), artifact.metadata());
    assertArrayEquals(bytes, artifact.body().openStream().readAllBytes());
    assertThrows(UnsupportedOperationException.class, () -> artifact.metadata().put("x", "y"));
  }

  @Test
  void compatibilityConstructorDefaultsToOkStatus() {
    Artifact artifact = new Artifact(0, Map.of(), () -> new ByteArrayInputStream(new byte[0]));

    assertEquals(200, artifact.statusCode());
  }

  @Test
  void rejectsMalformedValues() {
    ArtifactBody body = () -> new ByteArrayInputStream(new byte[0]);
    assertThrows(IllegalArgumentException.class, () -> new Artifact(199, 0, Map.of(), body));
    assertThrows(IllegalArgumentException.class, () -> new Artifact(600, 0, Map.of(), body));
    assertThrows(IllegalArgumentException.class, () -> new Artifact(200, -1, Map.of(), body));
    assertThrows(NullPointerException.class, () -> new Artifact(200, 0, null, body));
    assertThrows(NullPointerException.class, () -> new Artifact(200, 0, Map.of(), null));

    Map<String, String> nullKey = new LinkedHashMap<>();
    nullKey.put(null, "value");
    assertThrows(NullPointerException.class, () -> new Artifact(200, 0, nullKey, body));

    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("key", null);
    assertThrows(NullPointerException.class, () -> new Artifact(200, 0, nullValue, body));
  }
}
