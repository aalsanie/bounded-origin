package io.github.aalsanie.boundedorigin.api;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ArtifactBody extends AutoCloseable {
  InputStream openStream() throws IOException;

  /** Releases owned resources after all readers finish, or if the result is discarded. */
  @Override
  default void close() throws IOException {}
}
