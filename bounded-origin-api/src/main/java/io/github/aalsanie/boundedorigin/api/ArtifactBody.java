package io.github.aalsanie.boundedorigin.api;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ArtifactBody extends AutoCloseable {
  InputStream openStream() throws IOException;

  /**
   * Releases resources owned by this body. Resource-owning implementations must prevent new streams
   * and make close idempotent. Already-open streams retain their resources until closed.
   */
  @Override
  default void close() throws IOException {}
}
