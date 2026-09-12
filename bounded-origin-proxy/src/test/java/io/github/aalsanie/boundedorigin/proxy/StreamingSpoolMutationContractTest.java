package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingSpoolMutationContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void acceptedBytesTracksEveryAcceptedAppendExactly() throws Exception {
    SpoolQuota quota = new SpoolQuota(32, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "accepted-", 5, quota);
    try {
      spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join();
      assertEquals(3, spool.acceptedBytes());
      assertEquals(3, quota.bytes());

      spool.append(new byte[] {4, 5}).toCompletableFuture().join();
      assertEquals(5, spool.acceptedBytes());
      assertEquals(5, quota.bytes());

      assertThrows(
          StreamingSpool.BodyLimitExceededException.class,
          () -> spool.append(new byte[] {6}));
      assertEquals(5, spool.acceptedBytes());
      assertEquals(5, quota.bytes());
    } finally {
      spool.close();
      awaitQuotaReleased(quota);
    }
  }

  @Test
  void reservationReportsItsExactByteCount() {
    SpoolQuota quota = new SpoolQuota(32, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    try {
      assertEquals(0, reservation.bytes());
      reservation.reserve(7);
      assertEquals(7, reservation.bytes());
      assertEquals(7, quota.bytes());
    } finally {
      reservation.close();
      quota.close();
    }
  }

  @Test
  void closingTheOnlyOpenResultStreamPerformsDeferredCleanup() throws Exception {
    SpoolQuota quota = new SpoolQuota(32, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "reader-", 32, quota);
    spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    InputStream input = result.openStream();

    result.close();
    assertTrue(Files.exists(result.path()));
    assertEquals(3, quota.bytes());
    assertEquals(1, quota.files());

    input.close();

    assertFalse(Files.exists(result.path()));
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }

  @Test
  void finishingAResultPreservesTheExactLengthAndDigest() throws Exception {
    SpoolQuota quota = new SpoolQuota(32, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "result-", 32, quota);
    spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    try {
      assertEquals(3, result.length());
      assertEquals(
          "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
          result.sha256());
    } finally {
      result.close();
    }
  }

  private static void awaitQuotaReleased(SpoolQuota quota) throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
    while (quota.files() != 0 && System.nanoTime() - deadline < 0) {
      Thread.sleep(5);
    }
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }
}
