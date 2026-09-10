package io.github.aalsanie.boundedorigin.api;

import java.io.IOException;
import java.util.Optional;

public interface ArtifactStore {
  Optional<Artifact> get(OperationKey key) throws IOException;

  void put(OperationKey key, Artifact artifact) throws IOException;
}
