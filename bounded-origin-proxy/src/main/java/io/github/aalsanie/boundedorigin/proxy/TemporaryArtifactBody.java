package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class TemporaryArtifactBody implements ArtifactBody {
  private final StreamingSpool.Result spool;
  private final AtomicBoolean deleted = new AtomicBoolean();

  TemporaryArtifactBody(StreamingSpool.Result spool) {
    this.spool = Objects.requireNonNull(spool, "spool");
  }

  @Override
  public InputStream openStream() throws IOException {
    if (deleted.get()) {
      throw new IOException("temporary artifact was deleted");
    }
    return spool.openStream();
  }

  void delete() {
    if (deleted.compareAndSet(false, true)) {
      spool.close();
    }
  }

  boolean deleted() {
    return deleted.get();
  }
}
