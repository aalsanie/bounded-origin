package io.github.aalsanie.boundedorigin.store.fs;

public record FileSystemArtifactStoreStats(
    long storedBytes, long entryCount, long corruptionCount, long evictionCount) {
  public FileSystemArtifactStoreStats {
    if (storedBytes < 0 || entryCount < 0 || corruptionCount < 0 || evictionCount < 0) {
      throw new IllegalArgumentException("store statistics must be non-negative");
    }
  }
}
