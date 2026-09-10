package io.github.aalsanie.boundedorigin.api;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface ArtifactBody {
  InputStream openStream() throws IOException;
}
