package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreInvariantTest {
  private static final String DIGEST_A = "a".repeat(64);
  private static final String DIGEST_B = "b".repeat(64);

  @TempDir Path tempDirectory;

  @Test
  void constructorAndStatisticsValidateEveryIndependentBoundary() throws IOException {
    assertThrows(NullPointerException.class, () -> new FileSystemArtifactStore(null, 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileSystemArtifactStore(tempDirectory.resolve("zero-store"), 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileSystemArtifactStore(tempDirectory.resolve("negative-store"), -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileSystemArtifactStore(tempDirectory.resolve("negative-artifact"), 10, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileSystemArtifactStore(tempDirectory.resolve("oversize-artifact"), 10, 11));
    assertThrows(
        NullPointerException.class,
        () -> new FileSystemArtifactStore(tempDirectory.resolve("null-output"), 10, 0, null));

    assertEquals(
        new FileSystemArtifactStoreStats(0, 0, 0, 0),
        new FileSystemArtifactStoreStats(0, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(-1, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(0, -1, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(0, 0, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new FileSystemArtifactStoreStats(0, 0, 0, -1));
  }

  @Test
  void capacityAndAccountingGuardsUseExactBoundaries() throws Throwable {
    Path root = tempDirectory.resolve("accounting");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000, 100)) {
      Method exceedsCapacity = method(FileSystemArtifactStore.class, "exceedsCapacity", long.class);
      Method evictToFit =
          method(FileSystemArtifactStore.class, "evictToFit", long.class, String.class);
      Method releaseReader = method(FileSystemArtifactStore.class, "releaseReader", String.class);
      Method endWriter = method(FileSystemArtifactStore.class, "endWriter");
      Method maybeReleaseResources =
          method(FileSystemArtifactStore.class, "maybeReleaseResources");

      setLong(store, "storedBytes", 900L);
      assertFalse((Boolean) invoke(exceedsCapacity, store, 100L));
      assertTrue((Boolean) invoke(exceedsCapacity, store, 101L));
      setLong(store, "storedBytes", 0L);

      assertThrows(IOException.class, () -> invoke(evictToFit, store, 1_001L, null));
      assertThrows(IOException.class, () -> invoke(releaseReader, store, DIGEST_A));

      AtomicInteger activeWriters = atomicInteger(store, "activeWriters");
      assertEquals(0, activeWriters.get());
      assertThrows(IOException.class, () -> invoke(endWriter, store));
      activeWriters.set(0);

      Object openReaders = field(store, "openReaders");
      put(openReaders, DIGEST_A, new AtomicInteger(0));
      assertThrows(IOException.class, () -> invoke(releaseReader, store, DIGEST_A));
      remove(openReaders, DIGEST_A);

      AtomicInteger totalOpenReaders = atomicInteger(store, "totalOpenReaders");
      put(openReaders, DIGEST_B, new AtomicInteger(1));
      totalOpenReaders.set(0);
      assertThrows(IOException.class, () -> invoke(releaseReader, store, DIGEST_B));
      totalOpenReaders.set(0);

      invoke(maybeReleaseResources, store);
      setBoolean(store, "closed", true);
      activeWriters.set(1);
      invoke(maybeReleaseResources, store);
      activeWriters.set(0);
      totalOpenReaders.set(1);
      invoke(maybeReleaseResources, store);
      totalOpenReaders.set(0);
      setBoolean(store, "closed", false);
    }
  }

  @Test
  void removalMaintainsReferenceAndObjectAccountingAcrossEveryBranch() throws Throwable {
    Path preservedRoot = tempDirectory.resolve("preserved");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(preservedRoot, 10_000, 100)) {
      store.put(key("a"), artifact(bytes(16, 1)));
      StoreEntry entry = currentEntry(store, key("a"));
      Path object = onlyObject(preservedRoot);
      Method deleteUnreferencedObject =
          method(FileSystemArtifactStore.class, "deleteUnreferencedObject", String.class);
      Method removeEntry =
          method(FileSystemArtifactStore.class, "removeEntry", StoreEntry.class, String.class);

      invoke(deleteUnreferencedObject, store, entry.contentDigest());
      assertTrue(Files.isRegularFile(object));

      invoke(removeEntry, store, entry, entry.contentDigest());
      assertTrue(Files.isRegularFile(object));
      assertEquals(0, store.stats().entryCount());

      invoke(deleteUnreferencedObject, store, entry.contentDigest());
      assertFalse(Files.exists(object));
      assertEquals(0, store.stats().storedBytes());

      invoke(deleteUnreferencedObject, store, entry.contentDigest());
      invoke(removeEntry, store, entry, null);
    }

    Path generationRoot = tempDirectory.resolve("generation-mismatch");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(generationRoot, 10_000, 100)) {
      OperationKey operationKey = key("generation");
      store.put(operationKey, artifact(bytes(8, 2)));
      StoreEntry entry = currentEntry(store, operationKey);
      StoreEntry differentGeneration =
          new StoreEntry(
              entry.keyHash(),
              entry.key(),
              entry.statusCode(),
              entry.contentLength(),
              entry.contentDigest(),
              entry.generation() + 1,
              entry.metadata(),
              entry.entryFileSize());
      Method removeEntry =
          method(FileSystemArtifactStore.class, "removeEntry", StoreEntry.class, String.class);
      invoke(removeEntry, store, differentGeneration, null);
      assertTrue(store.get(operationKey).isPresent());
    }

    Path sharedRoot = tempDirectory.resolve("shared");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(sharedRoot, 10_000, 100)) {
      byte[] body = bytes(16, 3);
      OperationKey firstKey = key("first");
      OperationKey secondKey = key("second");
      store.put(firstKey, artifact(body));
      store.put(secondKey, artifact(body));
      StoreEntry first = currentEntry(store, firstKey);
      Method removeEntry =
          method(FileSystemArtifactStore.class, "removeEntry", StoreEntry.class, String.class);
      invoke(removeEntry, store, first, null);
      assertTrue(store.get(firstKey).isEmpty());
      assertTrue(store.get(secondKey).isPresent());
      assertTrue(Files.isRegularFile(onlyObject(sharedRoot)));
    }

    Path inconsistentRoot = tempDirectory.resolve("inconsistent");
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(inconsistentRoot, 10_000, 100)) {
      OperationKey operationKey = key("broken-ref");
      store.put(operationKey, artifact(bytes(8, 4)));
      StoreEntry entry = currentEntry(store, operationKey);
      Object references = field(store, "objectReferences");
      remove(references, entry.contentDigest());
      Method removeEntry =
          method(FileSystemArtifactStore.class, "removeEntry", StoreEntry.class, String.class);
      assertThrows(IOException.class, () -> invoke(removeEntry, store, entry, null));
    }
  }

  @Test
  void artifactBodyCannotBeOpenedAfterItsGenerationWasEvicted() throws IOException {
    Path root = tempDirectory.resolve("stale-body");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 500, 100)) {
      store.put(key("a"), artifact(bytes(100, 1)));
      Artifact stale = store.get(key("a")).orElseThrow();
      store.put(key("b"), artifact(bytes(100, 2)));
      store.put(key("c"), artifact(bytes(100, 3)));

      assertTrue(store.get(key("a")).isEmpty());
      assertThrows(IOException.class, () -> stale.body().openStream());
    }
  }

  @Test
  void codecEnforcesChecksumAndExactFieldSizeBoundaries() throws IOException {
    Path exact = Files.createFile(tempDirectory.resolve("exact-field.entry"));
    StoreEntry exactEntry =
        new StoreEntry(
            DIGEST_A,
            key("exact-field"),
            200,
            0,
            DIGEST_B,
            0,
            Map.of("x", "v".repeat(65_536)),
            0);
    long exactSize =
        StoreEntryCodec.write(exact, exactEntry, FileSystemArtifactStoreInvariantTest::open);
    assertEquals(exactEntry.withEntryFileSize(exactSize), StoreEntryCodec.read(exact, DIGEST_A));

    Path tooLarge = Files.createFile(tempDirectory.resolve("large-field.entry"));
    StoreEntry largeEntry =
        new StoreEntry(
            DIGEST_A,
            key("large-field"),
            200,
            0,
            DIGEST_B,
            0,
            Map.of("x", "v".repeat(65_537)),
            0);
    assertThrows(
        IOException.class,
        () ->
            StoreEntryCodec.write(
                tooLarge, largeEntry, FileSystemArtifactStoreInvariantTest::open));

    byte[] encoded = Files.readAllBytes(exact);
    Files.write(
        exact,
        java.util.Arrays.copyOf(encoded, encoded.length - 1),
        StandardOpenOption.TRUNCATE_EXISTING);
    assertThrows(IOException.class, () -> StoreEntryCodec.read(exact, DIGEST_A));
  }

  @Test
  void readerCloseIsIdempotentAndReleasesTheProcessLockOnce() throws IOException {
    Path root = tempDirectory.resolve("double-close");
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100);
    store.put(key("reader"), artifact(bytes(8, 5)));
    InputStream input = store.get(key("reader")).orElseThrow().body().openStream();
    store.close();
    input.close();
    input.close();

    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertTrue(reopened.get(key("reader")).isPresent());
    }
  }

  private static StoreEntry currentEntry(FileSystemArtifactStore store, OperationKey key)
      throws Throwable {
    Method keyHash = method(FileSystemArtifactStore.class, "keyHash", OperationKey.class);
    String hash = (String) invoke(keyHash, store, key);
    Object entries = field(store, "entries");
    return (StoreEntry) get(entries, hash);
  }

  private static Method method(Class<?> owner, String name, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Method method = owner.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method;
  }

  private static Object invoke(Method method, Object target, Object... arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException exception) {
      throw exception.getCause();
    }
  }

  private static Object field(Object target, String name) throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setLong(Object target, String name, long value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setLong(target, value);
  }

  private static void setBoolean(Object target, String name, boolean value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.setBoolean(target, value);
  }

  private static AtomicInteger atomicInteger(Object target, String name)
      throws ReflectiveOperationException {
    return (AtomicInteger) field(target, name);
  }

  private static Object get(Object map, Object key) throws ReflectiveOperationException {
    return Map.class.getMethod("get", Object.class).invoke(map, key);
  }

  private static void put(Object map, Object key, Object value)
      throws ReflectiveOperationException {
    Map.class.getMethod("put", Object.class, Object.class).invoke(map, key, value);
  }

  private static void remove(Object map, Object key) throws ReflectiveOperationException {
    Map.class.getMethod("remove", Object.class).invoke(map, key);
  }

  private static java.io.OutputStream open(Path path) throws IOException {
    return Files.newOutputStream(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }
}
