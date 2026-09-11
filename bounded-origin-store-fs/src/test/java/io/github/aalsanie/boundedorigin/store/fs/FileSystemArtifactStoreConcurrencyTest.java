package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.read;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreConcurrencyTest {
  @TempDir Path tempDirectory;

  @Test
  void competingSameKeyWritersConvergeOnOneImmutableEntry()
      throws InterruptedException, IOException {
    Path root = tempDirectory.resolve("same-key");
    byte[] body = bytes(4_096, 7);
    int writers = 128;
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000_000, 100_000)) {
      List<Thread> threads = new ArrayList<>();
      for (int index = 0; index < writers; index++) {
        Thread thread =
            Thread.ofVirtual()
                .start(
                    () -> {
                      ready.countDown();
                      try {
                        start.await();
                        store.put(key("shared"), artifact(body));
                      } catch (IOException | InterruptedException exception) {
                        failures.add(exception);
                        Thread.currentThread().interrupt();
                      }
                    });
        threads.add(thread);
      }

      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (Thread thread : threads) {
        thread.join();
      }

      assertTrue(failures.isEmpty(), failures::toString);
      assertEquals(1, store.stats().entryCount());
      assertEquals(1, regularFiles(root.resolve("entries")).size());
      assertEquals(1, regularFiles(root.resolve("objects")).size());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      assertArrayEquals(body, read(store.get(key("shared")).orElseThrow()));
    }
  }

  @Test
  void manyKeysSharingContentCreateOneObjectWithoutLostEntries()
      throws InterruptedException, IOException {
    Path root = tempDirectory.resolve("shared-object");
    byte[] body = bytes(1_024, 3);
    int writers = 64;
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000_000, 100_000)) {
      List<Thread> threads = new ArrayList<>();
      for (int index = 0; index < writers; index++) {
        int writer = index;
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        start.await();
                        store.put(key("key-" + writer), artifact(body));
                      } catch (IOException | InterruptedException exception) {
                        failures.add(exception);
                        Thread.currentThread().interrupt();
                      }
                    }));
      }
      start.countDown();
      for (Thread thread : threads) {
        thread.join();
      }

      assertTrue(failures.isEmpty(), failures::toString);
      assertEquals(writers, store.stats().entryCount());
      assertEquals(writers, regularFiles(root.resolve("entries")).size());
      assertEquals(1, regularFiles(root.resolve("objects")).size());
    }
  }

  @Test
  void closeRacingBodyStagingCannotReleaseProcessLockOrPublishAfterClose()
      throws InterruptedException, IOException {
    Path root = tempDirectory.resolve("close-race");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    Artifact blocked =
        new Artifact(
            4,
            Map.of(),
            () ->
                new InputStream() {
                  private boolean emitted;

                  @Override
                  public int read() {
                    throw new AssertionError("bulk reads expected");
                  }

                  @Override
                  public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (!emitted) {
                      emitted = true;
                      entered.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                      }
                      java.util.Arrays.fill(buffer, offset, offset + 4, (byte) 1);
                      return 4;
                    }
                    return -1;
                  }
                });

    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000);
    Thread writer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    store.put(key("blocked"), blocked);
                  } catch (Throwable throwable) {
                    writerFailure.set(throwable);
                  }
                });

    assertTrue(entered.await(5, TimeUnit.SECONDS));
    store.close();
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 10_000, 1_000));
    release.countDown();
    writer.join();

    assertTrue(writerFailure.get() instanceof IOException);
    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertTrue(reopened.get(key("blocked")).isEmpty());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
    }
  }

  @Test
  void bodyOpenFailureCannotDeadlockOrLeakProcessLock() throws IOException {
    Path root = tempDirectory.resolve("open-failure");
    byte[] body = bytes(16, 6);
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000);
    store.put(key("x"), artifact(body));
    Artifact stored = store.get(key("x")).orElseThrow();
    java.nio.file.Files.delete(StoreTestSupport.onlyObject(root));

    assertThrows(IOException.class, stored.body()::openStream);
    store.close();
    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertTrue(reopened.get(key("x")).isEmpty());
    }
  }

  @Test
  void closingReaderTwiceDoesNotCorruptReaderAccounting() throws IOException {
    Path root = tempDirectory.resolve("reader-close");
    byte[] body = bytes(16, 5);
    FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000);
    store.put(key("x"), artifact(body));
    InputStream input = store.get(key("x")).orElseThrow().body().openStream();
    input.close();
    input.close();
    store.close();

    try (FileSystemArtifactStore reopened = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      assertArrayEquals(body, read(reopened.get(key("x")).orElseThrow()));
    }
  }
}
