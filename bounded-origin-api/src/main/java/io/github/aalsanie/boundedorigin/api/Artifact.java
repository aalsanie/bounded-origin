package io.github.aalsanie.boundedorigin.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record Artifact(long contentLength, Map<String, String> metadata, ArtifactBody body) {
  public Artifact {
    if (contentLength < 0) {
      throw new IllegalArgumentException("contentLength must be non-negative");
    }
    metadata = immutableMetadata(metadata);
    body = Objects.requireNonNull(body, "body");
  }

  private static Map<String, String> immutableMetadata(Map<String, String> values) {
    Objects.requireNonNull(values, "metadata");
    Map<String, String> copy = new LinkedHashMap<>();
    values.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "metadata key");
          Objects.requireNonNull(value, "metadata value");
          copy.put(key, value);
        });
    return Collections.unmodifiableMap(copy);
  }
}
