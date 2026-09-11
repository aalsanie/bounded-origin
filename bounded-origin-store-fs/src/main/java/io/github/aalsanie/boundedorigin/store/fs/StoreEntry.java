package io.github.aalsanie.boundedorigin.store.fs;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

record StoreEntry(
    String keyHash,
    OperationKey key,
    int statusCode,
    long contentLength,
    String contentDigest,
    long generation,
    Map<String, String> metadata,
    long entryFileSize) {
  StoreEntry {
    keyHash = Objects.requireNonNull(keyHash, "keyHash");
    key = Objects.requireNonNull(key, "key");
    contentDigest = Objects.requireNonNull(contentDigest, "contentDigest");
    metadata = Map.copyOf(new TreeMap<>(Objects.requireNonNull(metadata, "metadata")));
    if (entryFileSize < 0) {
      throw new IllegalArgumentException("entryFileSize must be non-negative");
    }
  }

  StoreEntry withEntryFileSize(long size) {
    return new StoreEntry(
        keyHash, key, statusCode, contentLength, contentDigest, generation, metadata, size);
  }

  boolean matches(Artifact artifact, String digest) {
    return statusCode == artifact.statusCode()
        && contentLength == artifact.contentLength()
        && contentDigest.equals(digest)
        && metadata.equals(artifact.metadata());
  }
}
