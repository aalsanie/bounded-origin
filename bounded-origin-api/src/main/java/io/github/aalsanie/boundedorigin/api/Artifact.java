package io.github.aalsanie.boundedorigin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record Artifact(
    int statusCode, long contentLength, Map<String, String> metadata, ArtifactBody body) {
  public Artifact {
    if (statusCode < 200 || statusCode > 599) {
      throw new IllegalArgumentException("statusCode must be between 200 and 599");
    }
    if (contentLength < 0) {
      throw new IllegalArgumentException("contentLength must be non-negative");
    }
    metadata = immutableMetadata(metadata);
    body = Objects.requireNonNull(body, "body");
  }

  public Artifact(long contentLength, Map<String, String> metadata, ArtifactBody body) {
    this(200, contentLength, metadata, body);
  }

  @Override
  public Map<String, String> metadata() {
    return Map.copyOf(metadata);
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
    return Map.copyOf(copy);
  }
}
