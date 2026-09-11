package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.regularFiles;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStoreCrashTest {
  @TempDir Path tempDirectory;

  @Test
  void processLockRetryAcquiresAfterOwnerTerminates() throws IOException, InterruptedException {
    Path root = tempDirectory.resolve("retry-store");
    Path marker = tempDirectory.resolve("retry-marker");
    Process process = startCrashWriter(root, marker);

    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(marker));
    assertTrue(process.isAlive());

    Thread terminator =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(100);
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                  }
                  process.destroyForcibly();
                });

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000_000, 100_000)) {
      assertTrue(store.get(key("crash")).isEmpty());
    } finally {
      terminator.join();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void forcedProcessTerminationDuringBodyStagingNeverPublishesPartialArtifact()
      throws IOException, InterruptedException {
    Path root = tempDirectory.resolve("store");
    Path marker = tempDirectory.resolve("marker");
    Process process = startCrashWriter(root, marker);

    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(marker));
    assertTrue(process.isAlive());

    process.destroyForcibly();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000_000, 100_000)) {
      assertTrue(store.get(key("crash")).isEmpty());
      assertTrue(regularFiles(root.resolve("tmp")).isEmpty());
      assertTrue(regularFiles(root.resolve("entries")).isEmpty());
      assertTrue(regularFiles(root.resolve("objects")).isEmpty());
    }
  }

  @Test
  void processLockTimesOutWhileOwnerRemainsAlive() throws IOException, InterruptedException {
    Path root = tempDirectory.resolve("locked-store");
    Path marker = tempDirectory.resolve("locked-marker");
    Process process = startCrashWriter(root, marker);

    try {
      awaitStaging(process, marker);
      assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 1_000_000, 100_000));
      assertTrue(process.isAlive());
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void processLockWaitPreservesThreadInterruption() throws IOException, InterruptedException {
    Path root = tempDirectory.resolve("interrupted-store");
    Path marker = tempDirectory.resolve("interrupted-marker");
    Process process = startCrashWriter(root, marker);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();

    try {
      awaitStaging(process, marker);
      Thread opener =
          Thread.ofVirtual()
              .start(
                  () -> {
                    Thread.currentThread().interrupt();
                    try (FileSystemArtifactStore opened =
                        new FileSystemArtifactStore(root, 1_000_000, 100_000)) {
                      opened.stats();
                      failure.set(new AssertionError("lock acquisition unexpectedly succeeded"));
                    } catch (Throwable throwable) {
                      failure.set(throwable);
                    } finally {
                      interrupted.set(Thread.currentThread().isInterrupted());
                    }
                  });
      opener.join();

      assertTrue(failure.get() instanceof IOException);
      assertTrue(interrupted.get());
      assertTrue(process.isAlive());
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  private static void awaitStaging(Process process, Path marker)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(Files.exists(marker));
    assertTrue(process.isAlive());
  }

  private static Process startCrashWriter(Path root, Path marker) throws IOException {
    Path javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            java.io.File.separatorChar == '\\' ? "java.exe" : "java");
    return new ProcessBuilder(
            javaExecutable.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            CrashWriterMain.class.getName(),
            root.toString(),
            marker.toString())
        .redirectErrorStream(true)
        .start();
  }
}
