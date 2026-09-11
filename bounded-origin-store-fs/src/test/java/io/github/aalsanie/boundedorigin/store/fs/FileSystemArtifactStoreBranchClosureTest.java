package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.onlyObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreBranchClosureTest {
  private static final String DIGEST = "a".repeat(64);

  @TempDir Path tempDirectory;

  @Test
  void storeEntryRejectsEveryNullInvariant() {
    OperationKey operationKey = key("entry-null");

    assertThrows(
        NullPointerException.class,
        () -> new StoreEntry(null, operationKey, 200, 0, DIGEST, 0, Map.of(), 0));
    assertThrows(
        NullPointerException.class,
        () -> new StoreEntry(DIGEST, null, 200, 0, DIGEST, 0, Map.of(), 0));
    assertThrows(
        NullPointerException.class,
        () -> new StoreEntry(DIGEST, operationKey, 200, 0, null, 0, Map.of(), 0));
    assertThrows(
        NullPointerException.class,
        () -> new StoreEntry(DIGEST, operationKey, 200, 0, DIGEST, 0, null, 0));
  }

  @Test
  void storeMetadataCountAcceptsExactLimitAndRejectsNextValue() throws IOException {
    Path root = tempDirectory.resolve("metadata-count");
    Map<String, String> exact = metadata(256);
    Map<String, String> tooMany = metadata(257);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 100_000, 100)) {
      store.put(key("exact"), artifact(200, bytes(1, 1), exact));
      assertEquals(exact, store.get(key("exact")).orElseThrow().metadata());

      assertThrows(
          IOException.class, () -> store.put(key("too-many"), artifact(200, bytes(1, 2), tooMany)));
      assertEquals(1, store.stats().entryCount());
    }
  }

  @Test
  void operationKeyMismatchIsTreatedAsEntryCorruption() throws IOException {
    Path root = tempDirectory.resolve("key-mismatch");
    OperationKey requested = key("requested");

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(requested, artifact(bytes(8, 3)));
      Path object = onlyObject(root);
      Map<String, StoreEntry> entries = entries(store);
      StoreEntry original = entries.values().iterator().next();
      StoreEntry mismatched =
          new StoreEntry(
              original.keyHash(),
              key("different"),
              original.statusCode(),
              original.contentLength(),
              original.contentDigest(),
              original.generation(),
              original.metadata(),
              original.entryFileSize());
      entries.put(original.keyHash(), mismatched);

      assertThrows(IOException.class, () -> store.get(requested));
      assertEquals(0, store.stats().entryCount());
      assertEquals(1, store.stats().corruptionCount());
      assertFalse(Files.exists(object));
    }
  }

  @Test
  void corruptPinnedObjectIsDeferredUntilReaderRelease() throws IOException {
    Path root = tempDirectory.resolve("pinned-corrupt");
    byte[] body = bytes(32, 4);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(key("first"), artifact(body));
      Path object = onlyObject(root);
      InputStream reader = store.get(key("first")).orElseThrow().body().openStream();
      try {
        Files.write(object, bytes(body.length, 9), StandardOpenOption.TRUNCATE_EXISTING);

        assertThrows(IOException.class, () -> store.put(key("second"), artifact(body)));
        assertEquals(0, store.stats().entryCount());
        assertTrue(Files.exists(object));
      } finally {
        reader.close();
      }

      assertFalse(Files.exists(object));
      store.put(key("second"), artifact(body));
      assertTrue(store.get(key("second")).isPresent());
    }
  }

  @Test
  void accountingUnderflowAndRepeatedResourceReleaseAreGuarded() throws IOException {
    Method checkedSubtract =
        method(FileSystemArtifactStore.class, "checkedSubtract", long.class, long.class);
    assertEquals(4L, invoke(checkedSubtract, null, 9L, 5L));
    assertThrows(IOException.class, () -> invoke(checkedSubtract, null, 4L, 5L));

    Path root = tempDirectory.resolve("release-twice");
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100);
    store.close();
    Method releaseResources = method(FileSystemArtifactStore.class, "releaseResources");
    invoke(releaseResources, store);
  }

  private static Map<String, String> metadata(int count) {
    Map<String, String> values = new LinkedHashMap<>();
    for (int index = 0; index < count; index++) {
      values.put("x-" + index, "v");
    }
    return values;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, StoreEntry> entries(FileSystemArtifactStore store) {
    try {
      Field field = FileSystemArtifactStore.class.getDeclaredField("entries");
      field.setAccessible(true);
      return (Map<String, StoreEntry>) field.get(store);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private static Method method(Class<?> owner, String name, Class<?>... parameterTypes) {
    try {
      Method method = owner.getDeclaredMethod(name, parameterTypes);
      method.setAccessible(true);
      return method;
    } catch (NoSuchMethodException exception) {
      throw new AssertionError(exception);
    }
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
}
