package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreFinalMutationClosureTest {
  @TempDir Path tempDirectory;

  @Test
  void oversizedBodyFailsAtTheStreamingBoundaryNotOnlyAtFinalLengthCheck() throws IOException {
    try (FileSystemArtifactStore store =
        new FileSystemArtifactStore(tempDirectory.resolve("streaming-boundary"), 10_000, 100)) {
      Artifact oversized =
          new Artifact(
              200,
              1,
              Map.of(),
              () -> new ByteArrayInputStream(new byte[] {1, 2}));

      IOException failure = assertThrows(IOException.class, () -> store.put(key("oversized"), oversized));

      assertEquals("artifact body exceeds declared content length", failure.getMessage());
      assertTrue(store.get(key("oversized")).isEmpty());
    }
  }

  @Test
  void removingEntriesForOneObjectNeverTouchesAnotherAndCanPreserveTheTargetObject()
      throws Throwable {
    Path root = tempDirectory.resolve("targeted-object-removal");
    OperationKey targetKey = key("target");
    OperationKey unrelatedKey = key("unrelated");

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(targetKey, artifact(bytes(8, 1)));
      store.put(unrelatedKey, artifact(bytes(8, 2)));

      StoreEntry target = currentEntry(store, targetKey);
      StoreEntry unrelated = currentEntry(store, unrelatedKey);
      Path targetObject = objectPath(store, target.contentDigest());
      Path unrelatedObject = objectPath(store, unrelated.contentDigest());
      Method removeEntriesForObject =
          method(
              FileSystemArtifactStore.class,
              "removeEntriesForObject",
              String.class,
              boolean.class);

      invoke(removeEntriesForObject, store, target.contentDigest(), true);

      assertTrue(store.get(targetKey).isEmpty());
      assertTrue(store.get(unrelatedKey).isPresent());
      assertTrue(Files.isRegularFile(targetObject));
      assertTrue(Files.isRegularFile(unrelatedObject));
      assertEquals(1L, store.stats().entryCount());
    }
  }

  @Test
  void zeroReaderCorruptionDoesNotLeaveADeferredDeleteMarker() throws Throwable {
    Path root = tempDirectory.resolve("zero-reader-corruption");
    OperationKey operationKey = key("corrupt");

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 100)) {
      store.put(operationKey, artifact(bytes(8, 3)));
      StoreEntry detected = currentEntry(store, operationKey);
      Method removeCorruption =
          method(
              FileSystemArtifactStore.class,
              "removeCorruption",
              StoreEntry.class,
              boolean.class);

      invoke(removeCorruption, store, detected, true);

      assertTrue(store.get(operationKey).isEmpty());
      assertFalse(Files.exists(objectPath(store, detected.contentDigest())));
      assertFalse(pendingObjectDeletes(store).contains(detected.contentDigest()));
      assertEquals(1L, store.stats().corruptionCount());
    }
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

  private static Path objectPath(FileSystemArtifactStore store, String digest) throws Throwable {
    Method objectPath = method(FileSystemArtifactStore.class, "objectPath", String.class);
    return (Path) invoke(objectPath, store, digest);
  }

  private static Set<String> pendingObjectDeletes(FileSystemArtifactStore store)
      throws ReflectiveOperationException {
    Field field = FileSystemArtifactStore.class.getDeclaredField("pendingObjectDeletes");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Set<String> values = (Set<String>) field.get(store);
    return Set.copyOf(values);
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
}
