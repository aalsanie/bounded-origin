package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyEntry;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyObject;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.read;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreCoverageTest {
  private static final int ENTRY_MAGIC = 0x424f4531;
  private static final int ENTRY_VERSION = 1;

  @TempDir Path tempDirectory;

  @Test
  void acceptsEveryHttpTokenNameCharacterAndHorizontalTab() throws IOException {
    Path root = tempDirectory.resolve("metadata-token");
    String name = "AZaz09!#$%&'*+-.^_`|~";
    Map<String, String> metadata = Map.of(name, "one\ttwo");

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("safe"), artifact(200, bytes(1, 1), metadata));
      assertEquals(metadata, store.get(key("safe")).orElseThrow().metadata());
      assertThrows(
          IOException.class,
          () -> store.put(key("unicode"), artifact(200, bytes(1, 1), Map.of("é", "value"))));
      assertThrows(
          IOException.class,
          () -> store.put(key("control"), artifact(200, bytes(1, 1), Map.of("x", "a\u001fb"))));
    }
  }

  @Test
  void zeroLengthReadAndEmptyBodyAreHandledWithoutBuffering() throws IOException {
    Path root = tempDirectory.resolve("zero-read");
    byte[] body = bytes(7, 3);
    AtomicBoolean first = new AtomicBoolean(true);
    Artifact delayed =
        new Artifact(
            body.length,
            Map.of(),
            () ->
                new InputStream() {
                  private int offset;

                  @Override
                  public int read() {
                    throw new AssertionError("bulk reads expected");
                  }

                  @Override
                  public int read(byte[] buffer, int start, int length) {
                    if (first.compareAndSet(true, false)) {
                      return 0;
                    }
                    if (offset == body.length) {
                      return -1;
                    }
                    int count = Math.min(length, body.length - offset);
                    System.arraycopy(body, offset, buffer, start, count);
                    offset += count;
                    return count;
                  }
                });

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("delayed"), delayed);
      store.put(key("empty"), artifact(new byte[0]));
      assertArrayEquals(body, read(store.get(key("delayed")).orElseThrow()));
      assertArrayEquals(new byte[0], read(store.get(key("empty")).orElseThrow()));
    }
  }

  @Test
  void bodyOpenRuntimeAndOutputOpenFailuresLeaveNoState() throws IOException {
    Path runtimeRoot = tempDirectory.resolve("runtime-open");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(runtimeRoot, 10_000, 100)) {
      Artifact runtime =
          new Artifact(
              1,
              Map.of(),
              () -> {
                throw new IllegalStateException("producer failed");
              });
      assertThrows(IllegalStateException.class, () -> store.put(key("runtime"), runtime));
      assertEquals(0, store.stats().entryCount());
      assertTrue(regularFiles(runtimeRoot.resolve("tmp")).isEmpty());
    }

    Path outputRoot = tempDirectory.resolve("output-open");
    TempOutputFactory failing =
        path -> {
          throw new IOException("cannot open output");
        };
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(outputRoot, 10_000, 100, failing)) {
      assertThrows(IOException.class, () -> store.put(key("output"), artifact(bytes(1, 1))));
      assertEquals(0, store.stats().storedBytes());
      assertTrue(regularFiles(outputRoot.resolve("tmp")).isEmpty());
    }
  }

  @Test
  void validButChangedEntryIsRejectedAndRemoved() throws IOException {
    Path root = tempDirectory.resolve("changed-entry");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("x"), artifact(200, bytes(8, 2), Map.of("etag", "one")));
      Path path = onlyEntry(root);
      String hash = entryHash(path);
      StoreEntry current = StoreEntryCodec.read(path, hash);
      StoreEntry changed =
          new StoreEntry(
              current.keyHash(),
              current.key(),
              current.statusCode(),
              current.contentLength(),
              current.contentDigest(),
              current.generation(),
              Map.of("etag", "two"),
              0);
      StoreEntryCodec.write(path, changed, FileSystemArtifactStoreCoverageTest::openTruncated);

      assertThrows(IOException.class, () -> store.get(key("x")));
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(1, store.stats().corruptionCount());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
    }
  }

  @Test
  void corruptPreexistingCasObjectIsReplacedBeforePublication() throws IOException {
    Path root = tempDirectory.resolve("preexisting-object");
    byte[] body = bytes(32, 4);
    String digest = sha256(body);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      Path object = objectPath(root, digest);
      Files.createDirectories(
          Objects.requireNonNull(object.getParent(), "preexisting object parent"));
      Files.write(object, bytes(body.length, 9));

      store.put(key("x"), artifact(body));
      assertArrayEquals(body, read(store.get(key("x")).orElseThrow()));
      assertEquals(1, store.stats().corruptionCount());
      assertEquals(1, regularFiles(root.resolve("objects")).size());
    }
  }

  @Test
  void twoOpenReadersKeepResourcesUntilTheLastClose() throws IOException {
    Path root = tempDirectory.resolve("two-readers");
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100);
    store.put(key("x"), artifact(bytes(8, 6)));
    Artifact stored = store.get(key("x")).orElseThrow();
    InputStream first = stored.body().openStream();
    InputStream second = stored.body().openStream();

    store.close();
    first.close();
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 10_000, 100));
    second.close();

    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertTrue(reopened.get(key("x")).isPresent());
    }
  }

  @Test
  void closeAndNullContractsCoverLifecycleBoundaries() throws IOException {
    Path root = tempDirectory.resolve("lifecycle");
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100);
    assertThrows(NullPointerException.class, () -> store.get(null));
    assertThrows(NullPointerException.class, () -> store.put(null, artifact(bytes(1, 1))));
    assertThrows(NullPointerException.class, () -> store.put(key("x"), null));
    store.close();
    store.close();
    assertThrows(IOException.class, () -> store.put(key("x"), artifact(bytes(1, 1))));
    assertEquals(new FileSystemArtifactStoreStats(0, 0, 0, 0), store.stats());

    Path fileRoot = tempDirectory.resolve("not-a-directory");
    Files.write(fileRoot, bytes(1, 1));
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(fileRoot, 10_000, 100));
  }

  @Test
  void recoveryRemovesMalformedPathsNestedTempsAndInvalidDigests() throws IOException {
    Path root = tempDirectory.resolve("recovery-shapes");
    Path tempNested = root.resolve("tmp").resolve("nested").resolve("deep");
    Files.createDirectories(tempNested);
    Files.write(tempNested.resolve("left.tmp"), bytes(2, 1));

    String hash = digest('a');
    Path badEntry = root.resolve("entries").resolve("wrong").resolve(hash + ".entry");
    Files.createDirectories(Objects.requireNonNull(badEntry.getParent(), "bad entry parent"));
    Files.write(badEntry, bytes(4, 1));
    Path wrongExtension = root.resolve("entries").resolve("junk.txt");
    Files.createDirectories(
        Objects.requireNonNull(wrongExtension.getParent(), "wrong extension parent"));
    Files.write(wrongExtension, bytes(4, 1));

    Path malformedObject = root.resolve("objects").resolve("zz").resolve("not-a-digest");
    Files.createDirectories(
        Objects.requireNonNull(malformedObject.getParent(), "malformed object parent"));
    Files.write(malformedObject, bytes(2, 1));
    String uppercase = "A".repeat(64);
    Path uppercaseObject = root.resolve("objects").resolve("AA").resolve(uppercase);
    Files.createDirectories(
        Objects.requireNonNull(uppercaseObject.getParent(), "uppercase object parent"));
    Files.write(uppercaseObject, bytes(2, 1));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
      assertTrue(store.stats().corruptionCount() >= 2);
    }
  }

  @Test
  void generationBoundariesAreRejectedWithoutWraparound() throws IOException {
    Path root = tempDirectory.resolve("generation");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("existing"), artifact(bytes(1, 7)));
    }

    Path path = onlyEntry(root);
    String hash = entryHash(path);
    StoreEntry entry = StoreEntryCodec.read(path, hash);
    StoreEntry almostExhausted =
        new StoreEntry(
            entry.keyHash(),
            entry.key(),
            entry.statusCode(),
            entry.contentLength(),
            entry.contentDigest(),
            Long.MAX_VALUE - 1,
            entry.metadata(),
            0);
    StoreEntryCodec.write(
        path, almostExhausted, FileSystemArtifactStoreCoverageTest::openTruncated);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertThrows(IOException.class, () -> store.put(key("next"), artifact(bytes(1, 8))));
      assertTrue(store.get(key("existing")).isPresent());
    }

    StoreEntry exhausted =
        new StoreEntry(
            entry.keyHash(),
            entry.key(),
            entry.statusCode(),
            entry.contentLength(),
            entry.contentDigest(),
            Long.MAX_VALUE,
            entry.metadata(),
            0);
    StoreEntryCodec.write(path, exhausted, FileSystemArtifactStoreCoverageTest::openTruncated);
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 10_000, 100));
  }

  @Test
  void codecRejectsEachStructuralBoundary() throws IOException {
    assertThrows(IOException.class, () -> StoreEntryCodec.read(emptyFile("empty"), digest('a')));
    assertThrows(
        IOException.class,
        () -> StoreEntryCodec.read(entryWithVersion("version", ENTRY_VERSION + 1), digest('b')));
    assertThrows(
        IOException.class,
        () -> StoreEntryCodec.read(entryWithMetadataCount("negative-count", -1), digest('c')));
    assertThrows(
        IOException.class,
        () -> StoreEntryCodec.read(entryWithMetadataCount("large-count", 257), digest('d')));
    assertThrows(
        IOException.class,
        () -> StoreEntryCodec.read(entryWithFirstStringLength("negative-string", -1), digest('e')));
    assertThrows(
        IOException.class,
        () ->
            StoreEntryCodec.read(entryWithFirstStringLength("large-string", 65_537), digest('f')));
    assertThrows(
        IOException.class,
        () -> StoreEntryCodec.read(entryWithTruncatedFirstString(), digest('0')));
    assertThrows(
        IOException.class, () -> StoreEntryCodec.read(entryWithInvalidOperationKey(), digest('1')));
    assertThrows(
        IOException.class, () -> StoreEntryCodec.read(entryWithDuplicateMetadata(), digest('2')));
  }

  @Test
  void persistedEntryValidationRejectsEveryValueBoundary() throws IOException {
    Path root = tempDirectory.resolve("entry-validation");
    Files.createDirectories(root.resolve("entries"));

    writeStoreEntry(root, digest('0'), entry(digest('0'), digest('a'), -1, 200, 1, Map.of()));
    writeStoreEntry(root, digest('1'), entry(digest('1'), digest('a'), 0, 199, 1, Map.of()));
    writeStoreEntry(root, digest('2'), entry(digest('2'), digest('a'), 0, 600, 1, Map.of()));
    writeStoreEntry(root, digest('3'), entry(digest('3'), digest('a'), 0, 200, -1, Map.of()));
    writeStoreEntry(root, digest('4'), entry(digest('4'), digest('a'), 0, 200, 101, Map.of()));
    writeStoreEntry(root, digest('5'), entry(digest('5'), "x", 0, 200, 1, Map.of()));
    writeStoreEntry(
        root,
        digest('6'),
        entry(digest('6'), digest('a'), 0, 200, 1, Map.of("connection", "close")));
    writeStoreEntry(root, digest('7'), entry(digest('7'), digest('a'), 0, 200, 1, Map.of()));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertEquals(0, store.stats().entryCount());
      assertEquals(8, store.stats().corruptionCount());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
    }
  }

  @Test
  void missingAccountedObjectIsRemovedFromByteAccounting() throws IOException {
    Path root = tempDirectory.resolve("missing-accounted");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("x"), artifact(bytes(32, 1)));
      assertTrue(store.stats().storedBytes() > 0);
      Files.delete(onlyObject(root));

      assertThrows(IOException.class, () -> store.get(key("x")));
      assertTrue(store.get(key("x")).isEmpty());
      assertEquals(0, store.stats().storedBytes());
      assertEquals(0, store.stats().entryCount());
    }
  }

  @Test
  void fullyPinnedCapacityFailsWithoutEvictingLiveReaders() throws IOException {
    Path root = tempDirectory.resolve("fully-pinned");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      byte[] firstBody = bytes(100, 1);
      byte[] secondBody = bytes(100, 2);
      store.put(key("a"), artifact(firstBody));
      store.put(key("b"), artifact(secondBody));
      Artifact firstArtifact = store.get(key("a")).orElseThrow();
      Artifact secondArtifact = store.get(key("b")).orElseThrow();

      try (InputStream first = firstArtifact.body().openStream();
          InputStream second = secondArtifact.body().openStream()) {
        assertThrows(IOException.class, () -> store.put(key("c"), artifact(bytes(100, 3))));
        assertArrayEquals(firstBody, first.readAllBytes());
        assertArrayEquals(secondBody, second.readAllBytes());
        assertTrue(store.get(key("a")).isPresent());
        assertTrue(store.get(key("b")).isPresent());
        assertTrue(store.get(key("c")).isEmpty());
      }
    }
  }

  @Test
  void entryWriteAndEntryPublishFailuresRollbackAllTemporaryState() throws IOException {
    Path writeRoot = tempDirectory.resolve("entry-write-failure");
    TempOutputFactory entryWriteFailure =
        path -> {
          String fileName =
              Objects.requireNonNull(path.getFileName(), "temporary file name").toString();
          if (fileName.startsWith("entry-")) {
            throw new IOException("entry output failed");
          }
          return openTruncated(path);
        };
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(writeRoot, 10_000, 100, entryWriteFailure)) {
      assertThrows(IOException.class, () -> store.put(key("write"), artifact(bytes(8, 1))));
      assertEquals(0, store.stats().storedBytes());
      assertEquals(0, store.stats().entryCount());
      assertTrue(regularFiles(writeRoot.resolve("tmp")).isEmpty());
      assertTrue(regularFiles(writeRoot.resolve("objects")).isEmpty());
    }

    Path publishRoot = tempDirectory.resolve("entry-publish-failure");
    OperationKey operationKey = key("publish");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(publishRoot, 10_000, 100)) {
      Path target = entryPath(publishRoot, keyHash(operationKey));
      Files.createDirectories(target);

      assertThrows(IOException.class, () -> store.put(operationKey, artifact(bytes(8, 2))));
      assertEquals(0, store.stats().storedBytes());
      assertEquals(0, store.stats().entryCount());
      assertTrue(regularFiles(publishRoot.resolve("tmp")).isEmpty());
      assertTrue(regularFiles(publishRoot.resolve("objects")).isEmpty());
    }
  }

  @Test
  void codecWriteEnforcesMetadataCountFieldAndTotalEncodedSize() throws IOException {
    Path countPath = Files.createFile(tempDirectory.resolve("codec-many"));
    Map<String, String> tooMany = new LinkedHashMap<>();
    for (int index = 0; index < 257; index++) {
      tooMany.put("k" + index, "v");
    }
    StoreEntry countEntry =
        new StoreEntry(digest('a'), key("many"), 200, 0, digest('b'), 0, tooMany, 0);
    assertThrows(
        IOException.class,
        () ->
            StoreEntryCodec.write(
                countPath, countEntry, FileSystemArtifactStoreCoverageTest::openTruncated));

    Path totalPath = Files.createFile(tempDirectory.resolve("codec-total"));
    String large = "x".repeat(65_000);
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("a", large);
    metadata.put("b", large);
    metadata.put("c", large);
    metadata.put("d", large);
    metadata.put("e", large);
    StoreEntry totalEntry =
        new StoreEntry(digest('c'), key("total"), 200, 0, digest('d'), 0, metadata, 0);
    assertThrows(
        IOException.class,
        () ->
            StoreEntryCodec.write(
                totalPath, totalEntry, FileSystemArtifactStoreCoverageTest::openTruncated));
  }

  @Test
  void internalValueObjectsRejectTheirRemainingInvalidBoundary() {
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(-1, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StoreEntry(digest('a'), key("x"), 200, 0, digest('b'), 0, Map.of(), -1));
  }

  private Path emptyFile(String name) throws IOException {
    return Files.createFile(tempDirectory.resolve(name));
  }

  private Path entryWithVersion(String name, int version) throws IOException {
    Path path = tempDirectory.resolve(name);
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
      output.writeInt(ENTRY_MAGIC);
      output.writeInt(version);
    }
    return path;
  }

  private Path entryWithMetadataCount(String name, int count) throws IOException {
    Path path = tempDirectory.resolve(name);
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
      writeValidEntryPrefix(output);
      output.writeInt(count);
    }
    return path;
  }

  private Path entryWithFirstStringLength(String name, int length) throws IOException {
    Path path = tempDirectory.resolve(name);
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
      output.writeInt(ENTRY_MAGIC);
      output.writeInt(ENTRY_VERSION);
      output.writeLong(0);
      output.writeInt(200);
      output.writeLong(1);
      output.writeInt(length);
    }
    return path;
  }

  private Path entryWithTruncatedFirstString() throws IOException {
    Path path = tempDirectory.resolve("truncated-string");
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
      output.writeInt(ENTRY_MAGIC);
      output.writeInt(ENTRY_VERSION);
      output.writeLong(0);
      output.writeInt(200);
      output.writeLong(1);
      output.writeInt(4);
      output.write(new byte[] {1, 2});
    }
    return path;
  }

  private Path entryWithInvalidOperationKey() throws IOException {
    Path path = tempDirectory.resolve("invalid-operation-key");
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
      output.writeInt(ENTRY_MAGIC);
      output.writeInt(ENTRY_VERSION);
      output.writeLong(0);
      output.writeInt(200);
      output.writeLong(1);
      writeString(output, digest('a'));
      writeString(output, "");
      output.writeLong(0);
      writeString(output, "semantic");
      writeString(output, "materializer");
      output.writeInt(0);
    }
    return path;
  }

  private Path entryWithDuplicateMetadata() throws IOException {
    Path path = tempDirectory.resolve("duplicate-metadata");
    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
      writeValidEntryPrefix(output);
      output.writeInt(2);
      writeString(output, "etag");
      writeString(output, "one");
      writeString(output, "etag");
      writeString(output, "two");
    }
    return path;
  }

  private static void writeValidEntryPrefix(DataOutputStream output) throws IOException {
    output.writeInt(ENTRY_MAGIC);
    output.writeInt(ENTRY_VERSION);
    output.writeLong(0);
    output.writeInt(200);
    output.writeLong(1);
    writeString(output, digest('a'));
    writeString(output, "policy");
    output.writeLong(7);
    writeString(output, "semantic");
    writeString(output, "materializer-v3");
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static StoreEntry entry(
      String keyHash,
      String contentDigest,
      long generation,
      int statusCode,
      long contentLength,
      Map<String, String> metadata) {
    return new StoreEntry(
        keyHash,
        key("entry-" + keyHash.charAt(0)),
        statusCode,
        contentLength,
        contentDigest,
        generation,
        metadata,
        0);
  }

  private static void writeStoreEntry(Path root, String hash, StoreEntry entry) throws IOException {
    Path path = entryPath(root, hash);
    Files.createDirectories(Objects.requireNonNull(path.getParent(), "entry path parent"));
    StoreEntryCodec.write(path, entry, FileSystemArtifactStoreCoverageTest::openTruncated);
  }

  private static OutputStream openTruncated(Path path) throws IOException {
    return Files.newOutputStream(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static Path entryPath(Path root, String hash) {
    return root.resolve("entries")
        .resolve(hash.substring(0, 2))
        .resolve(hash.substring(2, 4))
        .resolve(hash + ".entry");
  }

  private static String entryHash(Path path) {
    String name = Objects.requireNonNull(path.getFileName(), "entry file name").toString();
    return name.substring(0, name.length() - ".entry".length());
  }

  private static Path objectPath(Path root, String digest) {
    return root.resolve("objects")
        .resolve(digest.substring(0, 2))
        .resolve(digest.substring(2, 4))
        .resolve(digest);
  }

  private static String keyHash(OperationKey operationKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateString(digest, operationKey.policyId());
      updateLong(digest, operationKey.policyVersion());
      updateString(digest, operationKey.semanticIdentity());
      updateString(digest, operationKey.materializerVersion());
      return toHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void updateString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    updateInt(digest, bytes.length);
    digest.update(bytes);
  }

  private static void updateInt(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static void updateLong(MessageDigest digest, long value) {
    digest.update((byte) (value >>> 56));
    digest.update((byte) (value >>> 48));
    digest.update((byte) (value >>> 40));
    digest.update((byte) (value >>> 32));
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static String sha256(byte[] value) {
    try {
      return toHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte current : bytes) {
      value.append(String.format("%02x", current & 0xff));
    }
    return value.toString();
  }

  private static String digest(char value) {
    return String.valueOf(value).repeat(64);
  }
}
