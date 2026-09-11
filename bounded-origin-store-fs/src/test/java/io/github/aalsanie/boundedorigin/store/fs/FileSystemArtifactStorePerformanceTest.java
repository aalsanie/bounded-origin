package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStorePerformanceTest {
  @TempDir Path tempDirectory;

  @Test
  void warmArtifactReadPathDoesNotRegressCatastrophically() throws IOException {
    int reads = 100;
    byte[] body = bytes(64 * 1024, 7);
    Path root = tempDirectory.resolve("warm-read");

    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(root, 16L * 1024 * 1024, body.length)) {
      store.put(key("hot"), artifact(body));
      readFully(store.get(key("hot")).orElseThrow());

      long started = System.nanoTime();
      for (int index = 0; index < reads; index++) {
        Artifact stored = store.get(key("hot")).orElseThrow();
        assertEquals(body.length, readFully(stored));
      }
      long elapsed = System.nanoTime() - started;

      assertTrue(
          elapsed < Duration.ofSeconds(15).toNanos(),
          () -> "100 warm 64 KiB reads took " + Duration.ofNanos(elapsed));
    }
  }

  private static long readFully(Artifact artifact) throws IOException {
    try (InputStream input = artifact.body().openStream()) {
      return input.transferTo(OutputStream.nullOutputStream());
    }
  }
}
