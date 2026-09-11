package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreEvictionRaceTest {
  @TempDir Path tempDirectory;

  @Test
  void concurrentWritersCannotEvictPinnedReaderAndLeaveStoreWithinQuota()
      throws IOException, InterruptedException {
    Path root = tempDirectory.resolve("eviction-race");
    int writers = 32;
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 600, 100)) {
      store.put(key("pinned"), artifact(bytes(100, 1)));
      store.put(key("initial"), artifact(bytes(100, 2)));
      InputStream pinned = store.get(key("pinned")).orElseThrow().body().openStream();
      try {
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < writers; index++) {
          int writer = index;
          threads.add(
              Thread.ofVirtual()
                  .start(
                      () -> {
                        ready.countDown();
                        try {
                          start.await();
                          store.put(key("writer-" + writer), artifact(bytes(100, writer + 3)));
                        } catch (InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          failures.add(exception);
                        } catch (Throwable throwable) {
                          failures.add(throwable);
                        }
                      }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) {
          thread.join();
        }

        assertTrue(failures.isEmpty(), failures::toString);
        assertTrue(store.get(key("pinned")).isPresent());
        assertTrue(store.stats().storedBytes() <= 600);
        assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      } finally {
        pinned.close();
      }

      store.put(key("after-unpin"), artifact(bytes(100, 99)));
      assertTrue(store.get(key("pinned")).isEmpty());
      assertTrue(store.get(key("after-unpin")).isPresent());
      assertTrue(store.stats().storedBytes() <= 600);
    }
  }
}
