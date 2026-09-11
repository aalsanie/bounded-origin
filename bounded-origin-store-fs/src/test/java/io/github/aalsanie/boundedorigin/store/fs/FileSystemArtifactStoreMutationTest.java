package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyObject;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.read;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreMutationTest {
  @TempDir Path tempDirectory;

  @Test
  void codecRoundTripPreservesEveryPersistedField() throws IOException {
    Path path = Files.createFile(tempDirectory.resolve("entry.tmp"));
    OperationKey operationKey = new OperationKey("p", 9, "semantic", "m2");
    StoreEntry entry =
        new StoreEntry(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            operationKey,
            206,
            123,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            77,
            Map.of("content-type", "application/octet-stream", "etag", "abc"),
            0);
    long size =
        StoreEntryCodec.write(
            path,
            entry,
            output ->
                Files.newOutputStream(
                    output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
    StoreEntry decoded = StoreEntryCodec.read(path, entry.keyHash());

    assertTrue(size > 0);
    assertEquals(entry.withEntryFileSize(size), decoded);
    assertEquals(206, decoded.statusCode());
    assertEquals(123, decoded.contentLength());
    assertEquals(77, decoded.generation());
    assertEquals(operationKey, decoded.key());
    assertEquals(entry.contentDigest(), decoded.contentDigest());
    assertEquals(entry.metadata(), decoded.metadata());
    assertTrue(
        decoded.matches(
            new Artifact(206, 123, entry.metadata(), () -> InputStream.nullInputStream()),
            entry.contentDigest()));
    assertFalse(
        decoded.matches(
            new Artifact(200, 123, entry.metadata(), () -> InputStream.nullInputStream()),
            entry.contentDigest()));
    assertFalse(
        decoded.matches(
            new Artifact(206, 122, entry.metadata(), () -> InputStream.nullInputStream()),
            entry.contentDigest()));
    assertFalse(
        decoded.matches(
            new Artifact(206, 123, Map.of(), () -> InputStream.nullInputStream()),
            entry.contentDigest()));
    assertFalse(
        decoded.matches(
            new Artifact(206, 123, entry.metadata(), () -> InputStream.nullInputStream()),
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
  }

  @Test
  void codecRejectsBadHeadersTruncationTrailingBytesAndOversizeEntries() throws IOException {
    Path badHeader = tempDirectory.resolve("bad-header");
    Files.write(badHeader, bytes(64, 1));
    assertThrows(IOException.class, () -> StoreEntryCodec.read(badHeader, digest('a')));

    Path truncated = tempDirectory.resolve("truncated");
    Files.write(truncated, new byte[] {0x42, 0x4f, 0x45, 0x31, 0, 0, 0, 1});
    assertThrows(IOException.class, () -> StoreEntryCodec.read(truncated, digest('a')));

    Path valid = Files.createFile(tempDirectory.resolve("valid"));
    StoreEntry entry = entry(digest('a'), digest('b'), 1, Map.of());
    StoreEntryCodec.write(
        valid,
        entry,
        output ->
            Files.newOutputStream(
                output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
    Files.write(valid, new byte[] {1}, StandardOpenOption.APPEND);
    assertThrows(IOException.class, () -> StoreEntryCodec.read(valid, digest('a')));

    Path huge = tempDirectory.resolve("huge");
    try (var output = Files.newOutputStream(huge)) {
      output.write(0);
    }
    try (var channel = java.nio.channels.FileChannel.open(huge, StandardOpenOption.WRITE)) {
      channel.position(StoreEntryCodec.MAX_ENTRY_BYTES);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
    }
    assertThrows(IOException.class, () -> StoreEntryCodec.read(huge, digest('a')));
  }

  @Test
  void exactArtifactAndCapacityBoundariesAreObservable() throws IOException {
    Path root = tempDirectory.resolve("boundaries");
    byte[] exact = bytes(4, 1);
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000, 4)) {
      store.put(key("exact"), artifact(exact));
      assertArrayEquals(exact, read(store.get(key("exact")).orElseThrow()));
      assertThrows(IOException.class, () -> store.put(key("too-large"), artifact(bytes(5, 2))));
      assertTrue(store.stats().storedBytes() <= 1_000);
    }

    Path noRoom = tempDirectory.resolve("no-room");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(noRoom, 1, 1)) {
      assertThrows(IOException.class, () -> store.put(key("x"), artifact(bytes(1, 1))));
      assertEquals(0, store.stats().entryCount());
      assertEquals(0, store.stats().storedBytes());
    }
  }

  @Test
  void statusMetadataAndOperationKeyAllAffectImmutableEntryIdentity() throws IOException {
    Path root = tempDirectory.resolve("identity");
    byte[] body = bytes(8, 5);
    Artifact original = artifact(201, body, Map.of("etag", "one"));
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("x"), original);
      store.put(key("x"), original);
      assertThrows(
          IOException.class, () -> store.put(key("x"), artifact(202, body, Map.of("etag", "one"))));
      assertThrows(
          IOException.class, () -> store.put(key("x"), artifact(201, body, Map.of("etag", "two"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("x"), artifact(201, bytes(8, 6), Map.of("etag", "one"))));
      assertEquals(1, store.stats().entryCount());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
    }
  }

  @Test
  void objectDeduplicationChangesOnlyEntryAccounting() throws IOException {
    Path root = tempDirectory.resolve("dedup");
    byte[] body = bytes(32, 3);
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("a"), artifact(body));
      long one = store.stats().storedBytes();
      store.put(key("b"), artifact(body));
      long two = store.stats().storedBytes();

      assertEquals(1, regularFiles(root.resolve("objects")).size());
      assertEquals(2, regularFiles(root.resolve("entries")).size());
      assertTrue(two > one);
      assertTrue(two - one < body.length + one);
    }
  }

  @Test
  void evictionUsesGenerationOrderAndMaintainsExactReferenceAccounting() throws IOException {
    Path root = tempDirectory.resolve("evict");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      store.put(key("a"), artifact(bytes(100, 1)));
      store.put(key("b"), artifact(bytes(100, 2)));
      store.put(key("c"), artifact(bytes(100, 3)));

      assertTrue(store.get(key("a")).isEmpty());
      assertTrue(store.get(key("b")).isPresent());
      assertTrue(store.get(key("c")).isPresent());
      assertEquals(2, store.stats().entryCount());
      assertEquals(1, store.stats().evictionCount());
      assertEquals(2, regularFiles(root.resolve("objects")).size());
      assertEquals(2, regularFiles(root.resolve("entries")).size());
    }
  }

  @Test
  void pinnedOldestObjectForcesEvictionOfNextEligibleGeneration() throws IOException {
    Path root = tempDirectory.resolve("pinned");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      store.put(key("a"), artifact(bytes(100, 1)));
      store.put(key("b"), artifact(bytes(100, 2)));
      InputStream pinned = store.get(key("a")).orElseThrow().body().openStream();
      try {
        store.put(key("c"), artifact(bytes(100, 3)));
        assertTrue(store.get(key("a")).isPresent());
        assertTrue(store.get(key("b")).isEmpty());
        assertTrue(store.get(key("c")).isPresent());
      } finally {
        pinned.close();
      }
    }
  }

  @Test
  void objectCorruptionInvalidatesEveryReferenceButEntryCorruptionDoesNot() throws IOException {
    Path objectRoot = tempDirectory.resolve("object-corrupt");
    byte[] body = bytes(16, 4);
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(objectRoot, 10_000, 100)) {
      store.put(key("a"), artifact(body));
      store.put(key("b"), artifact(body));
      Files.write(onlyObject(objectRoot), bytes(16, 9), StandardOpenOption.TRUNCATE_EXISTING);
      assertThrows(IOException.class, () -> store.get(key("a")));
      assertTrue(store.get(key("a")).isEmpty());
      assertTrue(store.get(key("b")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
    }

    Path entryRoot = tempDirectory.resolve("entry-corrupt");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(entryRoot, 10_000, 100)) {
      store.put(key("a"), artifact(body));
      store.put(key("b"), artifact(body));
      Path entry = regularFiles(entryRoot.resolve("entries")).getFirst();
      Files.write(entry, new byte[] {0}, StandardOpenOption.TRUNCATE_EXISTING);
      assertThrows(IOException.class, () -> store.get(key("a")));
      assertEquals(1, store.stats().entryCount());
      assertEquals(1, regularFiles(entryRoot.resolve("objects")).size());
    }
  }

  @Test
  void restartRebuildsGenerationReferencesBytesAndCleansGarbage() throws IOException {
    Path root = tempDirectory.resolve("recovery");
    byte[] first = bytes(100, 1);
    byte[] second = bytes(100, 2);
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("a"), artifact(first));
      store.put(key("b"), artifact(second));
    }
    Files.write(root.resolve("tmp").resolve("left.tmp"), bytes(3, 1));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      assertArrayEquals(first, read(store.get(key("a")).orElseThrow()));
      assertArrayEquals(second, read(store.get(key("b")).orElseThrow()));
      store.put(key("c"), artifact(bytes(100, 3)));
      assertTrue(store.get(key("a")).isEmpty());
      assertTrue(store.stats().storedBytes() <= 500);
      assertEquals(1, store.stats().evictionCount());
    }
  }

  @Test
  void badBodyContractsNeverReachPublication() throws IOException {
    Path root = tempDirectory.resolve("bad-body");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertThrows(
          IOException.class, () -> store.put(key("null"), new Artifact(0, Map.of(), () -> null)));
      assertThrows(
          IOException.class,
          () ->
              store.put(
                  key("long"),
                  new Artifact(2, Map.of(), () -> new ByteArrayInputStream(bytes(3, 1)))));
      assertThrows(
          IOException.class,
          () ->
              store.put(
                  key("short"),
                  new Artifact(3, Map.of(), () -> new ByteArrayInputStream(bytes(2, 1)))));
      assertEquals(0, store.stats().entryCount());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
    }
  }

  @Test
  void unsafeMetadataChecksAreCaseInsensitiveAndRejectLineBreaks() throws IOException {
    Path root = tempDirectory.resolve("unsafe");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertThrows(
          IOException.class,
          () -> store.put(key("a"), artifact(200, bytes(1, 1), Map.of("CoNnEcTiOn", "close"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("b"), artifact(200, bytes(1, 1), Map.of("x", "a\nb"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("c"), artifact(200, bytes(1, 1), Map.of("x\ry", "a"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("d"), artifact(200, bytes(1, 1), Map.of("", "a"))));
      Map<String, String> duplicateNames = new LinkedHashMap<>();
      duplicateNames.put("ETag", "one");
      duplicateNames.put("etag", "two");
      assertThrows(
          IOException.class, () -> store.put(key("e"), artifact(200, bytes(1, 1), duplicateNames)));
      assertThrows(
          IOException.class,
          () -> store.put(key("f"), artifact(200, bytes(1, 1), Map.of("x y", "a"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("g"), artifact(200, bytes(1, 1), Map.of("x", "a\u007fb"))));
      assertEquals(0, store.stats().entryCount());
    }
  }

  @Test
  void outputFailureAndProducerFailureAlwaysCleanTemporaryFiles() throws IOException {
    Path outputRoot = tempDirectory.resolve("output-failure");
    AtomicInteger writes = new AtomicInteger();
    TempOutputFactory outputFactory =
        path ->
            new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                if (writes.incrementAndGet() > 4) {
                  throw new IOException("disk full");
                }
              }
            };
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(outputRoot, 10_000, 100, outputFactory)) {
      assertThrows(IOException.class, () -> store.put(key("x"), artifact(bytes(16, 1))));
      assertTrue(regularFiles(outputRoot.resolve("tmp")).isEmpty());
      assertEquals(0, store.stats().storedBytes());
    }

    Path inputRoot = tempDirectory.resolve("input-failure");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(inputRoot, 10_000, 100)) {
      Artifact failing =
          new Artifact(
              16,
              Map.of(),
              () ->
                  new InputStream() {
                    @Override
                    public int read() throws IOException {
                      throw new IOException("producer failed");
                    }
                  });
      assertThrows(IOException.class, () -> store.put(key("x"), failing));
      assertTrue(regularFiles(inputRoot.resolve("tmp")).isEmpty());
      assertEquals(0, store.stats().entryCount());
    }
  }

  @Test
  void processLockAndCloseLifecycleAreEnforced() throws IOException {
    Path root = tempDirectory.resolve("lock");
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100);
    store.put(key("x"), artifact(bytes(8, 1)));
    InputStream reader = store.get(key("x")).orElseThrow().body().openStream();
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 10_000, 100));
    store.close();
    assertThrows(IOException.class, () -> store.get(key("x")));
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 10_000, 100));
    reader.close();

    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertTrue(reopened.get(key("x")).isPresent());
    }
  }

  @Test
  void constructorAndStatsValidationUseExactBoundaries() throws IOException {
    Path root = tempDirectory.resolve("args");
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, 1, -1));
    assertThrows(IllegalArgumentException.class, () -> new FileSystemArtifactStore(root, 1, 2));
    assertThrows(NullPointerException.class, () -> new FileSystemArtifactStore(null, 1, 1));
    assertThrows(NullPointerException.class, () -> new FileSystemArtifactStore(root, 1, 1, null));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(0, -1, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(0, 0, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(0, 0, 0, -1));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1, 1)) {
      assertEquals(new FileSystemArtifactStoreStats(0, 0, 0, 0), store.stats());
    }
  }

  @Test
  void metadataEntryCountAndEncodedSizeAreBounded() throws IOException {
    Path root = tempDirectory.resolve("metadata-bounds");
    Map<String, String> tooMany = new LinkedHashMap<>();
    for (int index = 0; index < 257; index++) {
      tooMany.put("k" + index, "v");
    }
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000_000, 100)) {
      assertThrows(
          IOException.class, () -> store.put(key("many"), artifact(200, bytes(1, 1), tooMany)));
      String huge = "x".repeat(70_000);
      assertThrows(
          IOException.class,
          () -> store.put(key("huge"), artifact(200, bytes(1, 1), Map.of("x", huge))));
    }
  }

  private static StoreEntry entry(
      String keyHash, String contentDigest, long generation, Map<String, String> metadata) {
    return new StoreEntry(keyHash, key("codec"), 200, 1, contentDigest, generation, metadata, 0);
  }

  private static String digest(char value) {
    return String.valueOf(value).repeat(64);
  }
}
