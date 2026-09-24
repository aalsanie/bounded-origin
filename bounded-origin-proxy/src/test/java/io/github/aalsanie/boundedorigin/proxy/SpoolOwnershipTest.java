package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpoolOwnershipTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void writerTerminatesAfterFinishOrDiscard(boolean completed) throws Exception {
    SpoolQuota quota = new SpoolQuota(4, 1);
    StreamingSpool spool = new StreamingSpool(directory, "origin-response-", 4, quota);
    var field = StreamingSpool.class.getDeclaredField("writer");
    field.setAccessible(true);
    ExecutorService writer = (ExecutorService) field.get(spool);
    spool.append(new byte[] {1, 2, 3, 4}).toCompletableFuture().join();
    if (completed) {
      StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
      try {
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(4, quota.bytes());
      } finally {
        result.close();
      }
    } else {
      spool.close();
      assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(0, quota.files());
    assertEquals(0, quota.bytes());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedDeletionRetainsRealDiskCapacityAfterDiscardOrResultClose(boolean completed)
      throws Exception {
    SpoolQuota quota = new SpoolQuota(4, 1);
    AtomicInteger deletions = new AtomicInteger();
    StreamingSpool spool =
        new StreamingSpool(
            directory,
            "request-body-",
            4,
            quota,
            path -> {
              deletions.incrementAndGet();
              throw new IOException("injected filesystem deletion failure");
            });
    spool.append(new byte[] {1, 2, 3, 4}).toCompletableFuture().join();
    if (completed) {
      StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
      result.close();
      result.close();
    } else {
      spool.close();
      spool.close();
    }
    assertEquals(1, deletions.get());
    assertEquals(4, quota.bytes());
    assertEquals(1, quota.files());
    assertThrows(
        SpoolQuota.SpoolLimitExceededException.class,
        () -> new StreamingSpool(directory, "request-body-", 4, quota));
    try (var entries = Files.newDirectoryStream(directory, "*.tmp")) {
      var iterator = entries.iterator();
      assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(iterator.next()));
      assertFalse(iterator.hasNext());
    }
    AtomicInteger released = new AtomicInteger();
    quota.closeWhenReleased(released::incrementAndGet);
    assertEquals(0, released.get());
    assertThrows(
        IllegalStateException.class, () -> quota.closeWhenReleased(released::incrementAndGet));
  }

  @Test
  void discardBeforeFinishTransferRetainsDirectoryUntilPendingWriteEnds() throws Exception {
    SpoolDirectory owner = new SpoolDirectory(directory);
    SpoolQuota quota = new SpoolQuota(4, 1);
    AtomicInteger deletions = new AtomicInteger();
    StreamingSpool spool =
        new StreamingSpool(
            directory,
            "origin-response-",
            4,
            quota,
            path -> {
              deletions.incrementAndGet();
              Files.deleteIfExists(path);
            });
    var field = StreamingSpool.class.getDeclaredField("writer");
    field.setAccessible(true);
    ExecutorService writer = (ExecutorService) field.get(spool);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var barrier =
        writer.submit(
            () -> {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
              }
            });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      spool.append(new byte[] {1, 2, 3, 4});
      var result = spool.finish().toCompletableFuture();
      spool.close();
      CompletableFuture<Void> directoryReleased = new CompletableFuture<>();
      quota.closeWhenReleased(
          () -> {
            owner.close();
            directoryReleased.complete(null);
          });
      assertFalse(directoryReleased.isDone());
      assertThrows(IOException.class, () -> new SpoolDirectory(directory));
      release.countDown();
      barrier.get(5, TimeUnit.SECONDS);
      assertThrows(CompletionException.class, result::join);
      directoryReleased.get(5, TimeUnit.SECONDS);
      assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
      assertEquals(1, deletions.get());
      assertEquals(0, quota.bytes());
      assertEquals(0, quota.files());
      SpoolDirectory next = new SpoolDirectory(directory);
      try (next) {
        assertThrows(IOException.class, () -> new SpoolDirectory(directory));
      }
    } finally {
      release.countDown();
      spool.close();
      owner.close();
    }
  }
}
