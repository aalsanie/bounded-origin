package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyEntry;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyObject;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreRecoveryTest {
  @TempDir Path tempDirectory;

  @Test
  void liveDigestMismatchRemovesAllEntriesReferencingCorruptObject() throws IOException {
    Path root = tempDirectory.resolve("digest");
    byte[] shared = bytes(64, 4);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("a"), artifact(shared));
      store.put(key("b"), artifact(shared));
      Path object = onlyObject(root);
      Files.write(object, bytes(64, 9), StandardOpenOption.TRUNCATE_EXISTING);

      assertThrows(IOException.class, () -> store.get(key("a")));
      assertTrue(store.get(key("a")).isEmpty());
      assertTrue(store.get(key("b")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
    }
  }

  @Test
  void liveTruncationIsNeverServed() throws IOException {
    Path root = tempDirectory.resolve("truncated");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("x"), artifact(bytes(64, 1)));
      Files.write(onlyObject(root), bytes(8, 1), StandardOpenOption.TRUNCATE_EXISTING);

      assertThrows(IOException.class, () -> store.get(key("x")));
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
    }
  }

  @Test
  void liveEntryCorruptionRemovesOnlyThatReference() throws IOException {
    Path root = tempDirectory.resolve("entry");
    byte[] shared = bytes(64, 2);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("a"), artifact(shared));
      store.put(key("b"), artifact(shared));
      Path entry = regularFiles(root.resolve("entries")).getFirst();
      Files.write(entry, new byte[] {0}, StandardOpenOption.TRUNCATE_EXISTING);

      assertThrows(IOException.class, () -> store.get(key("a")));
      assertEquals(1, store.stats().entryCount());
      assertEquals(1, regularFiles(root.resolve("objects")).size());
    }
  }

  @Test
  void restartRemovesCorruptEntryAndOrphanedObject() throws IOException {
    Path root = tempDirectory.resolve("restart-entry");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("x"), artifact(bytes(64, 3)));
    }
    Files.write(onlyEntry(root), new byte[] {1, 2, 3}, StandardOpenOption.TRUNCATE_EXISTING);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
    }
  }

  @Test
  void restartRemovesEntryForMissingOrTruncatedObject() throws IOException {
    Path root = tempDirectory.resolve("restart-object");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("x"), artifact(bytes(64, 3)));
    }
    Files.write(onlyObject(root), bytes(2, 3), StandardOpenOption.TRUNCATE_EXISTING);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
    }
  }

  @Test
  void restartCleansTemporaryAndUnreferencedObjectFiles() throws IOException {
    Path root = tempDirectory.resolve("cleanup");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertEquals(0, store.stats().entryCount());
    }

    Files.write(root.resolve("tmp").resolve("leftover.tmp"), bytes(5, 1));
    String digest = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    Path orphan = root.resolve("objects").resolve("aa").resolve("aa").resolve(digest);
    Files.createDirectories(orphan.getParent());
    Files.write(orphan, bytes(5, 2));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
      assertEquals(0, store.stats().entryCount());
    }
  }

  @Test
  void interruptedBodyWriteLeavesOnlyPreexistingArtifacts() throws IOException {
    Path root = tempDirectory.resolve("interrupted");
    byte[] stable = bytes(16, 1);
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("stable"), artifact(stable));
      Artifact interrupted =
          new Artifact(
              100,
              java.util.Map.of(),
              () ->
                  new InputStream() {
                    private int reads;

                    @Override
                    public int read() {
                      throw new AssertionError("bulk reads expected");
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                      if (reads++ == 0) {
                        java.util.Arrays.fill(buffer, offset, offset + 16, (byte) 7);
                        return 16;
                      }
                      throw new IOException("producer interrupted");
                    }
                  });

      assertThrows(IOException.class, () -> store.put(key("partial"), interrupted));
      assertTrue(store.get(key("partial")).isEmpty());
      assertEquals(1, store.stats().entryCount());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
    }
  }

  @Test
  void loweringCapacityOnRestartEvictsOldestPersistedGeneration() throws IOException {
    Path root = tempDirectory.resolve("lowered-capacity");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("a"), artifact(bytes(100, 1)));
      store.put(key("b"), artifact(bytes(100, 2)));
      store.put(key("c"), artifact(bytes(100, 3)));
    }

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      assertTrue(store.get(key("a")).isEmpty());
      assertTrue(store.get(key("b")).isPresent());
      assertTrue(store.get(key("c")).isPresent());
      assertEquals(1, store.stats().evictionCount());
    }
  }
}
