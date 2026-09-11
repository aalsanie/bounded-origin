package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreBoundaryTest {
  @TempDir Path tempDirectory;

  @Test
  void metadataAndDigestPredicatesUseExactBoundaries()
      throws ReflectiveOperationException, IOException {
    Method metadataName = method(FileSystemArtifactStore.class, "isMetadataName", String.class);
    Method metadataValue =
        method(FileSystemArtifactStore.class, "containsUnsafeMetadataValueCharacter", String.class);
    Method digest = method(FileSystemArtifactStore.class, "isDigest", String.class);

    for (String valid :
        new String[] {
          "a", "z", "A", "Z", "0", "9", "!", "#", "$", "%", "&", "'", "*", "+", "-", ".", "^", "_",
          "`", "|", "~"
        }) {
      assertTrue((Boolean) invoke(metadataName, null, valid), valid);
    }
    for (String invalid : new String[] {"", "/", ":", "@", "[", "{", " ", "é"}) {
      assertFalse((Boolean) invoke(metadataName, null, invalid), invalid);
    }

    assertTrue((Boolean) invoke(metadataValue, null, "\u0000"));
    assertTrue((Boolean) invoke(metadataValue, null, "\u0008"));
    assertFalse((Boolean) invoke(metadataValue, null, "\t"));
    assertTrue((Boolean) invoke(metadataValue, null, "\u001f"));
    assertFalse((Boolean) invoke(metadataValue, null, " "));
    assertFalse((Boolean) invoke(metadataValue, null, "~"));
    assertTrue((Boolean) invoke(metadataValue, null, "\u007f"));
    assertFalse((Boolean) invoke(metadataValue, null, "\u0080"));

    for (char valid : new char[] {'0', '9', 'a', 'f'}) {
      assertTrue((Boolean) invoke(digest, null, String.valueOf(valid).repeat(64)));
    }
    assertFalse((Boolean) invoke(digest, null, "a".repeat(63)));
    assertFalse((Boolean) invoke(digest, null, "a".repeat(65)));
    for (char invalid : new char[] {'/', ':', '`', 'g', 'A'}) {
      assertFalse((Boolean) invoke(digest, null, String.valueOf(invalid).repeat(64)));
    }
  }

  @Test
  void hashEncodingAndSizeArithmeticUseExactBytes()
      throws ReflectiveOperationException, IOException, NoSuchAlgorithmException {
    Method updateInt =
        method(FileSystemArtifactStore.class, "updateInt", MessageDigest.class, int.class);
    Method updateLong =
        method(FileSystemArtifactStore.class, "updateLong", MessageDigest.class, long.class);
    Method checkedAdd = method(FileSystemArtifactStore.class, "checkedAdd", long.class, long.class);
    Method checkedSubtract =
        method(FileSystemArtifactStore.class, "checkedSubtract", long.class, long.class);
    Method toHex = method(FileSystemArtifactStore.class, "toHex", byte[].class);
    Method keyHash = method(FileSystemArtifactStore.class, "keyHash", OperationKey.class);

    MessageDigest actualDigest = MessageDigest.getInstance("SHA-256");
    invoke(updateInt, null, actualDigest, 0x01020304);
    invoke(updateLong, null, actualDigest, 0x05060708090a0b0cL);
    byte[] expectedBytes = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c};
    assertArrayEquals(
        MessageDigest.getInstance("SHA-256").digest(expectedBytes), actualDigest.digest());

    assertEquals(0L, invoke(checkedAdd, null, 0L, 0L));
    assertEquals(3L, invoke(checkedAdd, null, 1L, 2L));
    assertEquals(Long.MAX_VALUE, invoke(checkedAdd, null, Long.MAX_VALUE, 0L));
    assertThrows(IOException.class, () -> invoke(checkedAdd, null, Long.MAX_VALUE, 1L));

    assertEquals(3L, invoke(checkedSubtract, null, 5L, 2L));
    assertEquals(0L, invoke(checkedSubtract, null, 2L, 2L));
    assertThrows(IOException.class, () -> invoke(checkedSubtract, null, 1L, 2L));

    byte[] value = {0x00, 0x0f, 0x10, (byte) 0xff};
    assertEquals("000f10ff", invoke(toHex, null, (Object) value));

    OperationKey operationKey = new OperationKey("policy", 7, "semantic", "materializer-v3");
    String expectedHash = operationKeyHash(operationKey);
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(tempDirectory.resolve("key-hash"), 10_000, 100)) {
      assertEquals(expectedHash, invoke(keyHash, store, operationKey));
    }
  }

  @Test
  void pathAndReaderHelpersPreserveCasLayoutAndCounts()
      throws ReflectiveOperationException, IOException {
    Path root = tempDirectory.resolve("paths");
    String digest = "abcdef" + "0".repeat(58);
    String keyHash = "012345" + "a".repeat(58);

    Method objectPath = method(FileSystemArtifactStore.class, "objectPath", String.class);
    Method entryPath = method(FileSystemArtifactStore.class, "entryPath", String.class);
    Method entryHash = method(FileSystemArtifactStore.class, "entryHash", Path.class);
    Method objectDigest = method(FileSystemArtifactStore.class, "objectDigest", Path.class);
    Method readerCount = method(FileSystemArtifactStore.class, "readerCount", String.class);
    Method deleteTemporary = method(FileSystemArtifactStore.class, "deleteTemporary", Path.class);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      Path expectedObject = root.toAbsolutePath().normalize().resolve("objects/ab/cd/" + digest);
      Path expectedEntry =
          root.toAbsolutePath().normalize().resolve("entries/01/23/" + keyHash + ".entry");
      assertEquals(expectedObject, invoke(objectPath, store, digest));
      assertEquals(expectedEntry, invoke(entryPath, store, keyHash));
      assertEquals(keyHash, invoke(entryHash, store, expectedEntry));
      assertEquals(null, invoke(entryHash, store, expectedEntry.resolveSibling(keyHash + ".bin")));
      assertEquals(null, invoke(entryHash, store, expectedEntry.resolveSibling("bad.entry")));
      assertEquals(digest, invoke(objectDigest, store, expectedObject));
      assertEquals(null, invoke(objectDigest, store, expectedObject.resolveSibling("bad")));

      store.put(key("reader"), artifact(bytes(8, 4)));
      String storedDigest = objectName(root);
      assertEquals(0, invoke(readerCount, store, storedDigest));
      InputStream input = store.get(key("reader")).orElseThrow().body().openStream();
      try {
        assertEquals(1, invoke(readerCount, store, storedDigest));
      } finally {
        input.close();
      }
      assertEquals(0, invoke(readerCount, store, storedDigest));

      Path temporary = Files.createTempFile(root.resolve("tmp"), "boundary-", ".tmp");
      invoke(deleteTemporary, null, temporary);
      assertFalse(Files.exists(temporary));
      invoke(deleteTemporary, null, new Object[] {null});
    }
  }

  @Test
  void corruptionFlagAndReaderCloseHaveObservableSemantics() throws IOException {
    assertTrue(new CorruptStoreException("object", true).objectCorruption());
    assertFalse(new CorruptStoreException("entry", false).objectCorruption());

    Path root = tempDirectory.resolve("reader-close");
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100);
    store.put(key("reader-close"), artifact(bytes(8, 5)));
    InputStream input = store.get(key("reader-close")).orElseThrow().body().openStream();
    assertEquals(5, input.read());
    store.close();
    input.close();
    assertThrows(IOException.class, input::read);

    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 100)) {
      assertTrue(reopened.get(key("reader-close")).isPresent());
    }
  }

  @Test
  void limitedOutputStreamEnforcesCapacityAndDelegatesLifecycle()
      throws ReflectiveOperationException, IOException {
    Class<?> type =
        Class.forName(
            "io.github.aalsanie.boundedorigin.store.fs.StoreEntryCodec$LimitedOutputStream");
    Constructor<?> constructor = type.getDeclaredConstructor(OutputStream.class, long.class);
    constructor.setAccessible(true);
    Method writeOne = type.getDeclaredMethod("write", int.class);
    Method writeMany = type.getDeclaredMethod("write", byte[].class, int.class, int.class);
    Method flush = type.getDeclaredMethod("flush");
    Method close = type.getDeclaredMethod("close");
    writeOne.setAccessible(true);
    writeMany.setAccessible(true);
    flush.setAccessible(true);
    close.setAccessible(true);

    TrackingOutputStream delegate = new TrackingOutputStream();
    Object output = constructor.newInstance(delegate, 4L);
    invoke(writeOne, output, 0x01);
    invoke(writeMany, output, new byte[] {0x02, 0x03, 0x04}, 0, 3);
    assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04}, delegate.bytes());
    assertThrows(IOException.class, () -> invoke(writeOne, output, 0x05));
    assertThrows(IOException.class, () -> invoke(writeMany, output, new byte[] {0x01}, 0, -1));

    invoke(flush, output);
    assertTrue(delegate.flushed);
    invoke(close, output);
    assertTrue(delegate.closed);
  }

  private static Method method(Class<?> owner, String name, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Method method = owner.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method;
  }

  private static Object invoke(Method method, Object target, Object... arguments)
      throws IOException {
    try {
      return method.invoke(target, arguments);
    } catch (IllegalAccessException exception) {
      throw new AssertionError(exception);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      throw new AssertionError(cause);
    }
  }

  private static String objectName(Path root) throws IOException {
    try (var paths = Files.walk(root.resolve("objects"))) {
      Path object = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
      return Objects.requireNonNull(object.getFileName(), "object file name").toString();
    }
  }

  private static String operationKeyHash(OperationKey key) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    updateString(digest, key.policyId());
    updateLong(digest, key.policyVersion());
    updateString(digest, key.semanticIdentity());
    updateString(digest, key.materializerVersion());
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void updateString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(
        new byte[] {
          (byte) (bytes.length >>> 24),
          (byte) (bytes.length >>> 16),
          (byte) (bytes.length >>> 8),
          (byte) bytes.length
        });
    digest.update(bytes);
  }

  private static void updateLong(MessageDigest digest, long value) {
    digest.update(
        new byte[] {
          (byte) (value >>> 56),
          (byte) (value >>> 48),
          (byte) (value >>> 40),
          (byte) (value >>> 32),
          (byte) (value >>> 24),
          (byte) (value >>> 16),
          (byte) (value >>> 8),
          (byte) value
        });
  }

  private static final class TrackingOutputStream extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean flushed;
    private boolean closed;

    @Override
    public void write(int value) {
      bytes.write(value);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
      bytes.write(buffer, offset, length);
    }

    @Override
    public void flush() {
      flushed = true;
    }

    @Override
    public void close() {
      closed = true;
    }

    byte[] bytes() {
      return bytes.toByteArray();
    }
  }
}
