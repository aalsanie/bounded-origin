package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReaderOwnershipRaceTest {
  @TempDir Path directory;

  @Test
  void closingLastReaderCannotRemoveAConcurrentReadersOwnership()
      throws IOException, ReflectiveOperationException, InterruptedException {
    FileSystemArtifactStore store = new FileSystemArtifactStore(directory, 600, 100);
    AtomicReference<InputStream> opened = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch removing = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    Thread opener =
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    assertTrue(removing.await(5, TimeUnit.SECONDS));
                    opened.set(StoreTestSupport.open(store.get(key("pinned")).orElseThrow()));
                  } catch (Throwable exception) {
                    failure.set(exception);
                  } finally {
                    finished.countDown();
                  }
                });
    ReentrantReadWriteLock stateLock = (ReentrantReadWriteLock) field("stateLock").get(store);
    field("openReaders")
        .set(
            store,
            new PausingReaders(
                () -> {
                  removing.countDown();
                  long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                  // Either the competing open completes, or the production lock prevents it.
                  while (finished.getCount() != 0
                      && !stateLock.hasQueuedThread(opener)
                      && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                  }
                  assertTrue(finished.getCount() == 0 || stateLock.hasQueuedThread(opener));
                }));
    try {
      store.put(key("pinned"), artifact(bytes(100, 1)));
      try (InputStream first = StoreTestSupport.open(store.get(key("pinned")).orElseThrow())) {
        assertEquals(1, first.read());
        opener.start();
      }
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      opener.join(5_000);
      assertFalse(opener.isAlive());
      assertEquals(null, failure.get());
      try (InputStream second = opened.getAndSet(null)) {
        for (int index = 0; index < 8; index++) {
          store.put(key("pressure-" + index), artifact(bytes(100, index + 2)));
        }
        assertTrue(StoreTestSupport.contains(store, key("pinned")));
        assertArrayEquals(bytes(100, 1), second.readAllBytes());
      }
      assertEquals(0, ((AtomicInteger) field("totalOpenReaders").get(store)).get());
      store.close();
      try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(directory, 600, 100)) {
        assertTrue(reopened.stats().storedBytes() <= 600);
      }
    } finally {
      removing.countDown();
      opener.join(5_000);
      try (InputStream abandoned = opened.get()) {
        if (abandoned != null) {
          assertFalse(opener.isAlive());
        }
      } finally {
        store.close();
        // A failing accounting assertion must not leave the fixture's process lock held.
        ((FileChannel) field("lockChannel").get(store)).close();
      }
    }
  }

  private static Field field(String name) throws NoSuchFieldException {
    Field field = FileSystemArtifactStore.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static final class PausingReaders extends ConcurrentHashMap<String, AtomicInteger> {
    private static final long serialVersionUID = 1L;
    private transient Runnable beforeRemove;

    private PausingReaders(Runnable beforeRemove) {
      this.beforeRemove = beforeRemove;
    }

    @Override
    public boolean remove(Object key, Object value) {
      Runnable action = beforeRemove;
      beforeRemove = null;
      if (action != null) {
        action.run();
      }
      return super.remove(key, value);
    }
  }
}
