package io.github.aalsanie.boundedorigin.api;

import java.io.IOException;
import java.util.Optional;

public interface ArtifactStore {
  /**
   * Acquires an owned artifact, protected from eviction until its body is closed. The caller must
   * close the body even if it never opens a stream. Streams have their own lifetimes.
   */
  Optional<Artifact> get(OperationKey key) throws IOException;

  /** Publishes immutable content without taking ownership of the supplied body. */
  void put(OperationKey key, Artifact artifact) throws IOException;
}
