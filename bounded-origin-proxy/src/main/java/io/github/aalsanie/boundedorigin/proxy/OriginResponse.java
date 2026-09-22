package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.util.Map;
import java.util.Objects;

record OriginResponse(Artifact artifact, Map<String, String> headers, boolean hasTrailers) {
  OriginResponse {
    Objects.requireNonNull(artifact, "artifact");
    headers = Map.copyOf(headers);
  }
}
