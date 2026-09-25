package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryOwnershipProcessTest {
  @TempDir Path directory;

  @Test
  void rejectedLocalDuplicatesCannotReleaseTheLiveOwnersProcessLock()
      throws IOException, InterruptedException {
    Path root = directory.resolve("store");
    try (var store = new FileSystemArtifactStore(root, 1024, 512)) {
      assertEquals(0, store.stats().entryCount());
      assertChildAcquisition(root, false);
      for (int attempt = 0; attempt < 2; attempt++) {
        Path alias = root.resolve(".");
        assertThrows(IOException.class, () -> new FileSystemArtifactStore(alias, 1024, 512));
        assertChildAcquisition(root, false);
      }
    }
    assertChildAcquisition(root, true);
  }

  @Test
  void deferredBodiesAndStreamsKeepBothOwnershipLayersAfterStoreClose()
      throws IOException, InterruptedException {
    Path root = directory.resolve("store");
    var store = new FileSystemArtifactStore(root, 1024, 512);
    try (store) {
      store.put(key("body"), artifact(bytes(8, 1)));
      var body = store.get(key("body")).orElseThrow().body();
      try (body) {
        store.close();
        assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 1024, 512));
        assertChildAcquisition(root, false);
        try (var stream = body.openStream()) {
          body.close();
          assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 1024, 512));
          assertChildAcquisition(root, false);
          assertArrayEquals(bytes(8, 1), stream.readAllBytes());
        }
      }
    }
    assertChildAcquisition(root, true);
  }

  @Test
  void concurrentRejectedOpenersCannotDropOwnership()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    Path root = directory.resolve("store");
    try (var store = new FileSystemArtifactStore(root, 1024, 512);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      assertEquals(0, store.stats().entryCount());
      var start = new CountDownLatch(1);
      var attempts = new ArrayList<Future<?>>();
      for (int index = 0; index < 8; index++) {
        attempts.add(
            callers.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  assertThrows(
                      IOException.class, () -> new FileSystemArtifactStore(root, 1024, 512));
                  return null;
                }));
      }
      start.countDown();
      for (var attempt : attempts) {
        attempt.get(10, TimeUnit.SECONDS);
      }
      assertChildAcquisition(root, false);
    }
    assertChildAcquisition(root, true);
  }

  @Test
  void directoryClaimUsesFilesystemIdentityForDistinctPathNames()
      throws IOException, ReflectiveOperationException {
    Path root = directory.resolve("identity");
    var owner = new FileSystemArtifactStore(root, 1024, 512);
    try (owner) {
      assertEquals(0, owner.stats().entryCount());
      Path alias = root.toRealPath().resolve(".");
      assertTrue(Files.isSameFile(root, alias));
      assertFalse(root.toRealPath().equals(alias));
      var claim = FileSystemArtifactStore.class.getDeclaredMethod("reserveDirectory", Path.class);
      claim.setAccessible(true);
      var owners = FileSystemArtifactStore.class.getDeclaredField("OWNERS");
      owners.setAccessible(true);
      try {
        // Test the identity operation independently of constructor path normalization.
        var failure =
            assertThrows(InvocationTargetException.class, () -> claim.invoke(null, alias));
        assertTrue(failure.getCause() instanceof IOException);
      } finally {
        ((Set<?>) owners.get(null)).remove(alias);
      }
    }
  }

  @Test
  void separateDirectoriesRemainIndependentAcrossReplacementAndOldClose() throws IOException {
    Path firstPath = directory.resolve("first");
    Path secondPath = directory.resolve("second");
    var first = new FileSystemArtifactStore(firstPath, 1024, 512);
    try (first;
        var second = new FileSystemArtifactStore(secondPath, 1024, 512)) {
      first.close();
      try (var replacement = new FileSystemArtifactStore(firstPath, 1024, 512)) {
        first.close();
        assertEquals(0, replacement.stats().entryCount());
        assertEquals(0, second.stats().entryCount());
        assertThrows(IOException.class, () -> new FileSystemArtifactStore(firstPath, 1024, 512));
        assertThrows(IOException.class, () -> new FileSystemArtifactStore(secondPath, 1024, 512));
      }
    }
  }

  @Test
  void failedDescriptorOpenReleasesOnlyItsOwnLocalReservation() throws IOException {
    Path root = directory.resolve("store");
    Path lock = Files.createDirectories(root.resolve(".lock"));
    assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 1024, 512));
    Files.delete(lock);
    try (var store = new FileSystemArtifactStore(root, 1024, 512)) {
      assertEquals(0, store.stats().entryCount());
      assertThrows(IOException.class, () -> new FileSystemArtifactStore(root, 1024, 512));
    }
    try (var reopened = new FileSystemArtifactStore(root, 1024, 512)) {
      assertEquals(0, reopened.stats().entryCount());
    }
  }

  static void assertChildAcquisition(Path root, boolean expected)
      throws IOException, InterruptedException {
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                LockAttempt.class.getName(),
                root.toString())
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), "ownership contender did not terminate");
      String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(expected ? 0 : 3, child.exitValue(), output);
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
    }
  }

  public static final class LockAttempt {
    private LockAttempt() {}

    public static void main(String[] args) {
      try (var store = new FileSystemArtifactStore(Path.of(args[0]), 1024, 512)) {
        System.out.println(store.stats().entryCount());
      } catch (IOException exception) {
        System.out.println(exception.getMessage());
        System.exit(3);
      }
    }
  }
}
