package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingSpoolTest {
  @TempDir Path tempDirectory;

  @Test
  void streamsToDiskCalculatesDigestAndReleasesQuota() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 2);
    StreamingSpool spool = new StreamingSpool(tempDirectory, "request-", 20, quota);
    spool.append("abc".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().join();
    spool.append("def".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().join();

    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();

    assertEquals(6, result.length());
    assertEquals(
        "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721", result.sha256());
    try (InputStream input = result.openStream()) {
      assertArrayEquals("abcdef".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
    }
    assertEquals(6, quota.bytes());
    assertEquals(1, quota.files());
    assertFalse(result.released());

    result.close();
    result.close();
    assertTrue(result.released());
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
    assertFalse(Files.exists(result.path()));
    assertThrows(java.io.IOException.class, result::openStream);
  }

  @Test
  void completedSpoolDefersDeletionUntilOpenReadersClose() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool spool = new StreamingSpool(tempDirectory, "reader-", 100, quota);
    spool.append("abcdef".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    InputStream input = result.openStream();

    result.close();
    assertTrue(result.released());
    assertTrue(Files.exists(result.path()));
    assertEquals(6, quota.bytes());
    assertEquals('a', input.read());

    input.close();
    assertFalse(Files.exists(result.path()));
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
    assertThrows(java.io.IOException.class, result::openStream);
  }

  @Test
  void perBodyAndGlobalLimitsAreDistinct() throws Exception {
    SpoolQuota quota = new SpoolQuota(4, 2);
    StreamingSpool bodyLimit = new StreamingSpool(tempDirectory, "body-", 2, quota);
    assertThrows(
        StreamingSpool.BodyLimitExceededException.class,
        () -> bodyLimit.append(new byte[] {1, 2, 3}));
    bodyLimit.close();

    StreamingSpool global = new StreamingSpool(tempDirectory, "global-", 10, quota);
    global.append(new byte[] {1, 2, 3, 4}).toCompletableFuture().join();
    assertThrows(
        StreamingSpool.SpoolCapacityExceededException.class, () -> global.append(new byte[] {5}));
    global.close();
  }

  @Test
  void fileLimitAndDiscardedFinishCleanEverything() throws Exception {
    SpoolQuota quota = new SpoolQuota(100, 1);
    StreamingSpool first = new StreamingSpool(tempDirectory, "one-", 100, quota);
    assertThrows(
        SpoolQuota.SpoolLimitExceededException.class,
        () -> new StreamingSpool(tempDirectory, "two-", 100, quota));

    first.append(new byte[] {1, 2, 3}).toCompletableFuture().join();
    var finish = first.finish().toCompletableFuture();
    StreamingSpool.Result result = finish.join();
    assertTrue(Files.exists(result.path()));
    result.close();
    assertEquals(0, quota.files());
  }

  @Test
  void closeDuringPendingWriteMakesFinishUnavailable() throws Exception {
    SpoolQuota quota = new SpoolQuota(10_000, 1);
    StreamingSpool spool = new StreamingSpool(tempDirectory, "discard-", 10_000, quota);
    spool.append(new byte[8_000]);
    spool.close();
    assertThrows(IllegalStateException.class, spool::finish);
  }

  @Test
  void invalidConstructionIsRejected() {
    SpoolQuota quota = new SpoolQuota(10, 1);
    assertThrows(
        IllegalArgumentException.class, () -> new StreamingSpool(tempDirectory, " ", 1, quota));
    assertThrows(
        IllegalArgumentException.class, () -> new StreamingSpool(tempDirectory, "x", -1, quota));
  }
}
