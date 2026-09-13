package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingSpoolFailureCleanupTest {
  @TempDir Path temporaryDirectory;

  @Test
  void asynchronousWriteFailureIsPreservedAndReleasesFileAndQuota() throws Exception {
    SpoolQuota quota = new SpoolQuota(64, 2);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "failed-write-", 64, quota);
    Path path = spoolPath(spool);

    fileChannel(spool).close();

    assertThrows(
        CompletionException.class,
        () -> spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join());
    assertThrows(CompletionException.class, () -> spool.finish().toCompletableFuture().join());

    awaitQuotaReleased(quota);
    assertFalse(Files.exists(path));
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
    quota.close();
  }

  private static FileChannel fileChannel(StreamingSpool spool) throws Exception {
    Field field = StreamingSpool.class.getDeclaredField("channel");
    field.setAccessible(true);
    return (FileChannel) field.get(spool);
  }

  private static Path spoolPath(StreamingSpool spool) throws Exception {
    Field field = StreamingSpool.class.getDeclaredField("path");
    field.setAccessible(true);
    return (Path) field.get(spool);
  }

  private static void awaitQuotaReleased(SpoolQuota quota) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (quota.files() != 0 && System.nanoTime() - deadline < 0) {
      Thread.sleep(1);
    }
  }
}
