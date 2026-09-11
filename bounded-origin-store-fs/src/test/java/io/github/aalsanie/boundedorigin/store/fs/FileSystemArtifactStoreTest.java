package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.read;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreTest {
  @TempDir Path tempDirectory;

  @Test
  void roundTripsStatusMetadataAndBodyAcrossRestart() throws IOException {
    Path root = tempDirectory.resolve("store");
    byte[] body = "persistent artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Map<String, String> metadata = Map.of("content-type", "text/plain", "etag", "v1");

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000_000, 100_000)) {
      store.put(key("round-trip"), artifact(201, body, metadata));
      Artifact stored = store.get(key("round-trip")).orElseThrow();

      assertEquals(201, stored.statusCode());
      assertEquals(body.length, stored.contentLength());
      assertEquals(metadata, stored.metadata());
      assertArrayEquals(body, read(stored));
      assertEquals(1, store.stats().entryCount());
      assertEquals(0, store.stats().corruptionCount());
    }

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000_000, 100_000)) {
      assertArrayEquals(body, read(store.get(key("round-trip")).orElseThrow()));
    }
  }

  @Test
  void deduplicatesBodiesAndKeepsOperationEntriesImmutable() throws IOException {
    Path root = tempDirectory.resolve("dedup");
    byte[] body = bytes(256, 7);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      store.put(key("one"), artifact(body));
      long firstSize = store.stats().storedBytes();
      store.put(key("one"), artifact(body));
      assertEquals(firstSize, store.stats().storedBytes());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());

      store.put(key("two"), artifact(body));
      assertEquals(2, store.stats().entryCount());
      assertEquals(1, regularFiles(root.resolve("objects")).size());
      assertEquals(2, regularFiles(root.resolve("entries")).size());

      assertThrows(IOException.class, () -> store.put(key("one"), artifact(bytes(256, 8))));
      assertThrows(IOException.class, () -> store.put(key("one"), artifact(202, body, Map.of())));
      assertEquals(2, store.stats().entryCount());
    }
  }

  @Test
  void enforcesDeclaredAndActualArtifactLimitsWithoutPartialPublication() throws IOException {
    Path root = tempDirectory.resolve("limits");
    AtomicInteger opened = new AtomicInteger();

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 2_000, 4)) {
      Artifact declaredTooLarge =
          new Artifact(
              5,
              Map.of(),
              () -> {
                opened.incrementAndGet();
                return new ByteArrayInputStream(bytes(5, 1));
              });
      assertThrows(IOException.class, () -> store.put(key("declared-large"), declaredTooLarge));
      assertEquals(0, opened.get());

      Artifact actualTooLarge =
          new Artifact(4, Map.of(), () -> new ByteArrayInputStream(bytes(5, 2)));
      Artifact truncated = new Artifact(4, Map.of(), () -> new ByteArrayInputStream(bytes(3, 3)));
      Artifact nullBody = new Artifact(0, Map.of(), () -> null);

      assertThrows(IOException.class, () -> store.put(key("actual-large"), actualTooLarge));
      assertThrows(IOException.class, () -> store.put(key("truncated"), truncated));
      assertThrows(IOException.class, () -> store.put(key("null"), nullBody));

      byte[] exact = bytes(4, 4);
      store.put(key("exact"), artifact(exact));
      assertArrayEquals(exact, read(store.get(key("exact")).orElseThrow()));
      assertEquals(1, store.stats().entryCount());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
    }
  }

  @Test
  void rejectsUnsafeResponseMetadata() throws IOException {
    Path root = tempDirectory.resolve("metadata");
    String[] unsafe = {
      "Connection",
      "Transfer-Encoding",
      "Date",
      "Set-Cookie",
      "Authorization",
      "Cookie",
      "Content-Length",
      "Upgrade"
    };

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      for (String name : unsafe) {
        Artifact value = artifact(200, bytes(1, 1), Map.of(name, "value"));
        assertThrows(IOException.class, () -> store.put(key("unsafe-" + name), value));
      }
      assertThrows(
          IOException.class,
          () -> store.put(key("newline-name"), artifact(200, bytes(1, 1), Map.of("x\ny", "v"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("newline-value"), artifact(200, bytes(1, 1), Map.of("x", "v\r\n"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("blank-name"), artifact(200, bytes(1, 1), Map.of(" ", "v"))));
      Map<String, String> duplicateNames = new LinkedHashMap<>();
      duplicateNames.put("ETag", "one");
      duplicateNames.put("etag", "two");
      assertThrows(
          IOException.class,
          () -> store.put(key("duplicate-name"), artifact(200, bytes(1, 1), duplicateNames)));
      assertThrows(
          IOException.class,
          () -> store.put(key("invalid-name"), artifact(200, bytes(1, 1), Map.of("x y", "v"))));

      Map<String, String> tooMany = new LinkedHashMap<>();
      for (int index = 0; index < 257; index++) {
        tooMany.put("x-" + index, "v");
      }
      assertThrows(
          IOException.class, () -> store.put(key("too-many"), artifact(200, bytes(1, 1), tooMany)));
      assertEquals(0, store.stats().entryCount());
    }
  }

  @Test
  void quotaEvictsOldestGenerationAndSkipsPinnedObjects() throws IOException {
    Path root = tempDirectory.resolve("eviction");
    byte[] first = bytes(100, 1);
    byte[] second = bytes(100, 2);
    byte[] third = bytes(100, 3);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      store.put(key("a"), artifact(first));
      store.put(key("b"), artifact(second));
      store.put(key("c"), artifact(third));

      assertTrue(store.get(key("a")).isEmpty());
      assertArrayEquals(second, read(store.get(key("b")).orElseThrow()));
      assertArrayEquals(third, read(store.get(key("c")).orElseThrow()));
      assertEquals(1, store.stats().evictionCount());
      assertTrue(store.stats().storedBytes() <= 500);
    }

    Path pinnedRoot = tempDirectory.resolve("pinned");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(pinnedRoot, 500, 100)) {
      store.put(key("a"), artifact(first));
      store.put(key("b"), artifact(second));
      Artifact pinnedArtifact = store.get(key("a")).orElseThrow();
      try (InputStream pinned = pinnedArtifact.body().openStream()) {
        store.put(key("c"), artifact(third));
        assertTrue(store.get(key("b")).isEmpty());
        assertFalse(store.get(key("a")).isEmpty());
        assertArrayEquals(first, pinned.readAllBytes());
      }
      assertArrayEquals(third, read(store.get(key("c")).orElseThrow()));
    }
  }

  @Test
  void capacityFailureDoesNotPublishAnEntry() throws IOException {
    Path root = tempDirectory.resolve("capacity");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1, 1)) {
      assertThrows(IOException.class, () -> store.put(key("x"), artifact(bytes(1, 1))));
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(0, store.stats().entryCount());
    }
  }

  @Test
  void outputFailureBehavesLikeDiskFullAndLeavesNoPartialArtifact() throws IOException {
    Path root = tempDirectory.resolve("disk-full");
    TempOutputFactory failing =
        path ->
            new OutputStream() {
              private int written;

              @Override
              public void write(int value) throws IOException {
                if (++written > 8) {
                  throw new IOException("No space left on device");
                }
              }

              @Override
              public void write(byte[] buffer, int offset, int length) throws IOException {
                for (int index = 0; index < length; index++) {
                  write(buffer[offset + index]);
                }
              }
            };

    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(root, 10_000, 1_000, failing)) {
      assertThrows(IOException.class, () -> store.put(key("disk-full"), artifact(bytes(32, 9))));
      assertTrue(store.get(key("disk-full")).isEmpty());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
    }
  }

  @Test
  void closeRejectsNewWorkAndDefersProcessUnlockUntilReadersClose() throws IOException {
    Path root = tempDirectory.resolve("close");
    byte[] body = bytes(32, 1);
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000);
    store.put(key("x"), artifact(body));
    Artifact stored = store.get(key("x")).orElseThrow();
    InputStream pinned = stored.body().openStream();

    store.close();
    store.close();
    assertThrows(IOException.class, () -> store.get(key("x")));
    assertThrows(IOException.class, () -> store.put(key("y"), artifact(body)));
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 10_000, 1_000));

    pinned.close();
    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertArrayEquals(body, read(reopened.get(key("x")).orElseThrow()));
    }
  }

  @Test
  void streamsLargeBodiesWithoutWholeResponseBuffering() throws IOException {
    Path root = tempDirectory.resolve("large");
    int length = 8 * 1024 * 1024;
    AtomicInteger bulkReads = new AtomicInteger();
    Artifact large =
        new Artifact(
            length,
            Map.of(),
            () ->
                new InputStream() {
                  private int remaining = length;

                  @Override
                  public int read() {
                    throw new AssertionError("single-byte reads are not allowed");
                  }

                  @Override
                  public int read(byte[] buffer, int offset, int requested) {
                    if (remaining == 0) {
                      return -1;
                    }
                    int count = Math.min(Math.min(requested, 4096), remaining);
                    java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0x5a);
                    remaining -= count;
                    bulkReads.incrementAndGet();
                    return count;
                  }
                });

    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(root, 10L * 1024 * 1024, length)) {
      store.put(key("large"), large);
      assertTrue(bulkReads.get() > 1_000);
      Artifact stored = store.get(key("large")).orElseThrow();
      try (InputStream input = stored.body().openStream()) {
        assertEquals(length, input.transferTo(OutputStream.nullOutputStream()));
      }
    }
  }

  @Test
  void validatesConstructorArgumentsAndStatistics() throws IOException {
    Path root = tempDirectory.resolve("args");
    assertThrows(NullPointerException.class, () -> new FileSystemArtifactStore(null, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, 1, -1));
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, 1, 2));
    assertThrows(NullPointerException.class, () -> new FileSystemArtifactStore(root, 1, 1, null));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(-1, 0, 0, 0));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000, 100)) {
      FileSystemArtifactStoreStats stats = store.stats();
      assertEquals(0, stats.storedBytes());
      assertEquals(0, stats.entryCount());
      assertEquals(0, stats.corruptionCount());
      assertEquals(0, stats.evictionCount());
    }
  }
}
