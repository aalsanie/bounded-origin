package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoredResultLifetimeTest {
  @TempDir Path root;

  @Test
  void lookupPinsResultBeforeAnyStreamIsOpened() throws IOException {
    try (var store = new FileSystemArtifactStore(root, 300, 100)) {
      store.put(key("first"), artifact(bytes(100, 1)));
      var first = store.get(key("first")).orElseThrow();
      try (var body = first.body()) {
        assertThrows(IOException.class, () -> store.put(key("second"), artifact(bytes(100, 2))));
        try (var stream = body.openStream()) {
          assertArrayEquals(bytes(100, 1), stream.readAllBytes());
        }
        assertTrue(store.stats().storedBytes() <= 300);
      }
      store.put(key("second"), artifact(bytes(100, 2)));
      assertTrue(store.get(key("first")).isEmpty());
    }
  }

  @Test
  void closingStorePreservesOwnedBodiesAndStreamsUntilTheirIndependentRelease() throws IOException {
    var store = new FileSystemArtifactStore(root, 300, 100);
    store.put(key("first"), artifact(bytes(100, 1)));
    var body = store.get(key("first")).orElseThrow().body();
    store.close();
    assertThrows(IOException.class, () -> store.get(key("first")));
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 300, 100));
    try (var stream = body.openStream()) {
      body.close();
      body.close();
      assertThrows(IOException.class, body::openStream);
      assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 300, 100));
      assertArrayEquals(bytes(100, 1), stream.readAllBytes());
    }
    try (var reopened = new FileSystemArtifactStore(root, 300, 100)) {
      assertArrayEquals(
          bytes(100, 1), StoreTestSupport.read(reopened.get(key("first")).orElseThrow()));
    }
  }

  @Test
  void separateLookupOwnersAndStreamsPreventEvictionUntilLastRelease() throws IOException {
    try (var store = new FileSystemArtifactStore(root, 300, 100)) {
      store.put(key("first"), artifact(bytes(100, 1)));
      var first = store.get(key("first")).orElseThrow().body();
      var second = store.get(key("first")).orElseThrow().body();
      first.close();
      assertThrows(IOException.class, () -> store.put(key("second"), artifact(bytes(100, 2))));
      try (var stream = second.openStream()) {
        second.close();
        assertThrows(IOException.class, () -> store.put(key("second"), artifact(bytes(100, 2))));
        assertArrayEquals(bytes(100, 1), stream.readAllBytes());
      }
      store.put(key("second"), artifact(bytes(100, 2)));
      assertEquals(1, store.stats().evictionCount());
      assertTrue(store.get(key("first")).isEmpty());
    }
  }

  @Test
  void knownCorruptionRejectsNewStreamsAndRetainsAccountingUntilOwnerRelease() throws IOException {
    try (var store = new FileSystemArtifactStore(root, 300, 100)) {
      store.put(key("first"), artifact(bytes(100, 1)));
      var body = store.get(key("first")).orElseThrow().body();
      Path object = StoreTestSupport.onlyObject(root);
      Files.write(object, bytes(100, 2));
      assertThrows(IOException.class, () -> store.get(key("first")));
      assertThrows(IOException.class, body::openStream);
      assertEquals(1, store.stats().corruptionCount());
      assertEquals(100, store.stats().storedBytes());
      assertTrue(Files.exists(object));
      body.close();
      assertEquals(0, store.stats().storedBytes());
      assertTrue(Files.notExists(object));
      store.put(key("replacement"), artifact(bytes(100, 3)));
    }
  }
}
