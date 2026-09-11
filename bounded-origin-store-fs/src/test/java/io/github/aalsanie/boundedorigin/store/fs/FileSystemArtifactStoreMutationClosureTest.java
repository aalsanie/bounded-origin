package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyObject;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.read;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreMutationClosureTest {
  private static final String DIGEST_A = "a".repeat(64);
  private static final String DIGEST_B = "b".repeat(64);

  @TempDir Path tempDirectory;

  @Test
  void zeroArtifactLimitAndUpperHttpStatusRemainValidBoundaries() throws IOException {
    Path zeroRoot = tempDirectory.resolve("zero-artifact-limit");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(zeroRoot, 1_000, 0)) {
      store.put(key("empty"), artifact(599, new byte[0], Map.of("etag", "")));
      Artifact stored = store.get(key("empty")).orElseThrow();
      assertEquals(599, stored.statusCode());
      assertArrayEquals(new byte[0], read(stored));
    }

    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(zeroRoot, 1_000, 0)) {
      Artifact stored = reopened.get(key("empty")).orElseThrow();
      assertEquals(599, stored.statusCode());
      assertEquals("", stored.metadata().get("etag"));
      assertArrayEquals(new byte[0], read(stored));
    }

    Path exactRoot = tempDirectory.resolve("exact-artifact-limit");
    try (FileSystemArtifactStore store = new FileSystemArtifactStore(exactRoot, 1_000, 1)) {
      store.put(key("exact"), artifact(599, bytes(1, 7), Map.of()));
      assertArrayEquals(bytes(1, 7), read(store.get(key("exact")).orElseThrow()));
    }
  }

  @Test
  void evictionAdmissionAcceptsExactlyTheWholeEmptyStoreCapacity() throws Throwable {
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(tempDirectory.resolve("exact-capacity"), 1_000, 100)) {
      Method evictToFit =
          method(FileSystemArtifactStore.class, "evictToFit", long.class, String.class);
      invoke(evictToFit, store, 1_000L, null);
      assertEquals(0L, store.stats().storedBytes());
      assertEquals(0L, store.stats().entryCount());
    }
  }

  @Test
  void removingEverySharedReferenceDeletesTheCasObjectAndAllAccounting() throws Throwable {
    Path root = tempDirectory.resolve("shared-reference-zero");
    byte[] body = bytes(16, 4);
    OperationKey firstKey = key("shared-first");
    OperationKey secondKey = key("shared-second");

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(firstKey, artifact(body));
      store.put(secondKey, artifact(body));
      Path object = onlyObject(root);
      StoreEntry first = currentEntry(store, firstKey);
      StoreEntry second = currentEntry(store, secondKey);
      Method removeEntry =
          method(FileSystemArtifactStore.class, "removeEntry", StoreEntry.class, String.class);

      invoke(removeEntry, store, first, null);
      assertTrue(Files.isRegularFile(object));
      assertEquals(1L, store.stats().entryCount());
      assertTrue(store.stats().storedBytes() > 0);

      invoke(removeEntry, store, second, null);
      assertFalse(Files.exists(object));
      assertEquals(0L, store.stats().entryCount());
      assertEquals(0L, store.stats().storedBytes());
      assertTrue(store.get(firstKey).isEmpty());
      assertTrue(store.get(secondKey).isEmpty());
    }
  }

  @Test
  void codecDistinguishesZeroExactMaximumAndOversizeEntryFiles() throws IOException {
    Path empty = Files.createFile(tempDirectory.resolve("empty.entry"));
    CorruptStoreException emptyFailure =
        assertThrows(CorruptStoreException.class, () -> StoreEntryCodec.read(empty, DIGEST_A));
    assertEquals("invalid entry size", emptyFailure.getMessage());
    assertFalse(emptyFailure.objectCorruption());

    Path exactMaximum = tempDirectory.resolve("exact-max.entry");
    try (FileChannel channel =
        FileChannel.open(
            exactMaximum,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
      channel.position(StoreEntryCodec.MAX_ENTRY_BYTES - 1);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    assertEquals(StoreEntryCodec.MAX_ENTRY_BYTES, Files.size(exactMaximum));
    CorruptStoreException exactFailure =
        assertThrows(
            CorruptStoreException.class, () -> StoreEntryCodec.read(exactMaximum, DIGEST_A));
    assertEquals("invalid entry header", exactFailure.getMessage());

    Path oversize = tempDirectory.resolve("oversize.entry");
    try (FileChannel channel =
        FileChannel.open(oversize, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(StoreEntryCodec.MAX_ENTRY_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    assertEquals(StoreEntryCodec.MAX_ENTRY_BYTES + 1, Files.size(oversize));
    CorruptStoreException oversizeFailure =
        assertThrows(CorruptStoreException.class, () -> StoreEntryCodec.read(oversize, DIGEST_A));
    assertEquals("invalid entry size", oversizeFailure.getMessage());
  }

  @Test
  void codecRoundTripAcceptsAZeroLengthPersistedString() throws IOException {
    Path path = Files.createFile(tempDirectory.resolve("empty-value.entry"));
    StoreEntry entry =
        new StoreEntry(
            DIGEST_A,
            key("empty-value"),
            599,
            0,
            DIGEST_B,
            0,
            Map.of("etag", ""),
            0);

    long size = StoreEntryCodec.write(path, entry, FileSystemArtifactStoreMutationClosureTest::open);
    StoreEntry decoded = StoreEntryCodec.read(path, DIGEST_A);

    assertEquals(entry.withEntryFileSize(size), decoded);
    assertEquals("", decoded.metadata().get("etag"));
  }

  @Test
  void limitedOutputStreamAcceptsZeroLengthWritesAtTheLimit() throws Throwable {
    Class<?> type =
        Class.forName(
            "io.github.aalsanie.boundedorigin.store.fs.StoreEntryCodec$LimitedOutputStream");
    Constructor<?> constructor = type.getDeclaredConstructor(OutputStream.class, long.class);
    constructor.setAccessible(true);
    Method writeOne = type.getDeclaredMethod("write", int.class);
    Method writeMany = type.getDeclaredMethod("write", byte[].class, int.class, int.class);
    writeOne.setAccessible(true);
    writeMany.setAccessible(true);

    ByteArrayOutputStream delegate = new ByteArrayOutputStream();
    Object output = constructor.newInstance(delegate, 1L);
    invoke(writeOne, output, 0x41);
    invoke(writeMany, output, new byte[] {0x42}, 0, 0);
    assertArrayEquals(new byte[] {0x41}, delegate.toByteArray());
    assertThrows(IOException.class, () -> invoke(writeOne, output, 0x42));
  }

  private static StoreEntry currentEntry(FileSystemArtifactStore store, OperationKey key)
      throws Throwable {
    Method keyHash = method(FileSystemArtifactStore.class, "keyHash", OperationKey.class);
    String hash = (String) invoke(keyHash, store, key);
    Field entriesField = FileSystemArtifactStore.class.getDeclaredField("entries");
    entriesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, StoreEntry> entries = (Map<String, StoreEntry>) entriesField.get(store);
    return entries.get(hash);
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

  private static OutputStream open(Path path) throws IOException {
    return Files.newOutputStream(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }
}
