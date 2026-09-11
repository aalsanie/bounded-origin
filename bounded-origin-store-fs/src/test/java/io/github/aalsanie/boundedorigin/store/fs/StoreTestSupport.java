package io.github.aalsanie.boundedorigin.store.fs;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class StoreTestSupport {
  private StoreTestSupport() {}

  static OperationKey key(String identity) {
    return new OperationKey("policy", 7, identity, "materializer-v3");
  }

  static Artifact artifact(byte[] bytes) {
    return artifact(200, bytes, Map.of());
  }

  static Artifact artifact(int statusCode, byte[] bytes, Map<String, String> metadata) {
    byte[] copy = bytes.clone();
    return new Artifact(statusCode, copy.length, metadata, () -> new ByteArrayInputStream(copy));
  }

  static byte[] bytes(int length, int value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  static byte[] read(Artifact artifact) throws IOException {
    try (InputStream input = artifact.body().openStream()) {
      return input.readAllBytes();
    }
  }

  static List<Path> regularFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths.filter(Files::isRegularFile).sorted().toList();
    }
  }

  static Path onlyObject(Path root) throws IOException {
    List<Path> objects = regularFiles(root.resolve("objects"));
    if (objects.size() != 1) {
      throw new AssertionError("expected exactly one object but found " + objects.size());
    }
    return objects.getFirst();
  }

  static Path onlyEntry(Path root) throws IOException {
    List<Path> entries = regularFiles(root.resolve("entries"));
    if (entries.size() != 1) {
      throw new AssertionError("expected exactly one entry but found " + entries.size());
    }
    return entries.getFirst();
  }
}
