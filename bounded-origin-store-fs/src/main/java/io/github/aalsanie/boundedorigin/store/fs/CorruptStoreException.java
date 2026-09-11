package io.github.aalsanie.boundedorigin.store.fs;

import java.io.IOException;

final class CorruptStoreException extends IOException {
  private static final long serialVersionUID = 1L;

  private final boolean objectCorruption;

  CorruptStoreException(String message, boolean objectCorruption) {
    super(message);
    this.objectCorruption = objectCorruption;
  }

  boolean objectCorruption() {
    return objectCorruption;
  }
}
