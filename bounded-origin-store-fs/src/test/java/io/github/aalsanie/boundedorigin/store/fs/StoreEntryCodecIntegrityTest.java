package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyEntry;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreEntryCodecIntegrityTest {
  @TempDir Path tempDirectory;

  @Test
  void restartRejectsStructurallyValidMetadataTampering() throws IOException {
    Path root = tempDirectory.resolve("metadata-integrity");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("x"), artifact(200, bytes(8, 4), Map.of("etag", "one")));
    }

    Path entry = onlyEntry(root);
    byte[] encoded = Files.readAllBytes(entry);
    int valueOffset = indexOf(encoded, "one".getBytes(StandardCharsets.UTF_8));
    if (valueOffset < 0) {
      throw new AssertionError("persisted metadata value was not found");
    }
    encoded[valueOffset] = 't';
    Files.write(entry, encoded, StandardOpenOption.TRUNCATE_EXISTING);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
    }
  }

  private static int indexOf(byte[] source, byte[] target) {
    for (int index = 0; index <= source.length - target.length; index++) {
      boolean matches = true;
      for (int offset = 0; offset < target.length; offset++) {
        if (source[index + offset] != target[offset]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return index;
      }
    }
    return -1;
  }
}
