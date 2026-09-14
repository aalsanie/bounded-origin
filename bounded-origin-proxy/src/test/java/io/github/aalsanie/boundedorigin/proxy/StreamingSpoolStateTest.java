package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingSpoolStateTest {
  @TempDir Path temporaryDirectory;

  @Test
  void constructionFailureReleasesItsQuotaReservation() throws Exception {
    Path file = temporaryDirectory.resolve("not-a-directory");
    Files.writeString(file, "x");
    SpoolQuota quota = new SpoolQuota(100, 1);

    assertThrows(IOException.class, () -> new StreamingSpool(file, "spool-", 10, quota));

    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }

  @Test
  void zeroLengthSpoolFinishesAndRejectsFurtherWrites() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "empty-", 0, quota);

    spool.append(new byte[0]).toCompletableFuture().join();
    assertEquals(0, spool.acceptedBytes());
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    try {
      assertEquals(0, result.length());
      assertThrows(IllegalStateException.class, () -> spool.append(new byte[0]));
      assertThrows(IllegalStateException.class, spool::finish);
    } finally {
      result.close();
    }
  }

  @Test
  void repeatedDiscardIsIdempotentAndReleasesQuota() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "discard-", 100, quota);
    spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join();

    spool.close();
    spool.close();

    awaitQuota(quota);
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }

  @Test
  void failedOpenRestoresReaderAccountingBeforeRelease() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "missing-", 100, quota);
    spool.append(new byte[] {1}).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    Files.delete(result.path());

    assertThrows(IOException.class, result::openStream);
    result.close();

    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }

  @Test
  void multipleReadersDelayCleanupAndReaderCloseIsIdempotent() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "readers-", 100, quota);
    spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    InputStream first = result.openStream();
    InputStream second = result.openStream();

    result.close();
    assertTrue(Files.exists(result.path()));
    first.close();
    first.close();
    assertTrue(Files.exists(result.path()));
    second.close();

    assertFalse(Files.exists(result.path()));
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }

  @Test
  void bodyLimitExceptionReportsTheConfiguredLimit() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "limit-", 2, quota);
    try {
      StreamingSpool.BodyLimitExceededException failure =
          assertThrows(
              StreamingSpool.BodyLimitExceededException.class,
              () -> spool.append(new byte[] {1, 2, 3}));
      assertEquals(2, failure.limit());
      assertThrows(NullPointerException.class, () -> spool.append(null));
    } finally {
      spool.close();
    }
  }

  @Test
  void resultRejectsInvalidConstruction() throws Exception {
    Path path = Files.createTempFile(temporaryDirectory, "result-", ".tmp");
    SpoolQuota quota = new SpoolQuota(100, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> new StreamingSpool.Result(path, -1, "digest", reservation));
      assertThrows(
          NullPointerException.class,
          () -> new StreamingSpool.Result(null, 0, "digest", reservation));
      assertThrows(
          NullPointerException.class, () -> new StreamingSpool.Result(path, 0, null, reservation));
      assertThrows(
          NullPointerException.class, () -> new StreamingSpool.Result(path, 0, "digest", null));
    } finally {
      reservation.close();
      Files.deleteIfExists(path);
    }
  }

  private static void awaitQuota(SpoolQuota quota) throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
    while (quota.files() != 0 && System.nanoTime() - deadline < 0) {
      Thread.sleep(5);
    }
  }
}
