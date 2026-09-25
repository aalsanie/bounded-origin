package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginDirectoryOwnershipProcessTest {
  @TempDir Path directory;

  @Test
  void failedAndUnstartedInstancesCannotReleaseTheLiveOwnersProcessLock()
      throws IOException, InterruptedException {
    Path root = directory.resolve("ownership");
    try (var first = registry(root)) {
      first.start();
      assertChildAcquisition(root, false);
      try (var unstarted = registry(root)) {
        assertEquals(0, unstarted.outstanding());
      }
      for (int attempt = 0; attempt < 2; attempt++) {
        var rejected = registry(root.resolve("."));
        try (rejected) {
          assertThrows(IOException.class, rejected::start);
          rejected.close();
        }
        assertChildAcquisition(root, false);
      }
      var permit = first.reserve(new OperationKey("p", 1, "one", "v1"), 1);
      permit.close();
      assertEquals(1, first.unresolved());
    }
    assertChildAcquisition(root, true);
    try (var restored = registry(root)) {
      restored.start();
      assertEquals(1, restored.outstanding());
      assertEquals(1, restored.unresolved());
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> restored.reserve(new OperationKey("p", 1, "distinct", "v1"), 1));
    }
  }

  private static OriginWorkRegistry registry(Path root) {
    return new OriginWorkRegistry(
        GatewayConfig.from(
            Map.of(
                "origin.host", "127.0.0.1",
                "origin.port", "1",
                "temporary.directory", root.resolveSibling("temporary").toString(),
                "origin.completion-contract", "RESPONSE_COMPLETE",
                "origin.ownership-directory", root.toString(),
                "origin.max-active", "1")),
        new GatewayMetrics());
  }

  @Test
  void concurrentDuplicatesAndOldCloseCannotDropReplacementOwnership()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    Path root = directory.resolve("ownership");
    var first = registry(root);
    try (first) {
      first.start();
    }
    try (var replacement = registry(root);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      replacement.start();
      first.close();
      var start = new CountDownLatch(1);
      var attempts = new ArrayList<Future<?>>();
      for (int index = 0; index < 8; index++) {
        attempts.add(
            callers.submit(
                () -> {
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  try (var rejected = registry(root)) {
                    assertThrows(IOException.class, rejected::start);
                  }
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
    var owner = registry(root);
    try (owner) {
      owner.start();
      Path alias = root.toRealPath().resolve(".");
      assertTrue(Files.isSameFile(root, alias));
      assertFalse(root.toRealPath().equals(alias));
      var claim = OriginWorkRegistry.class.getDeclaredMethod("reserveDirectory", Path.class);
      claim.setAccessible(true);
      var owners = OriginWorkRegistry.class.getDeclaredField("OWNERS");
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
    var first = registry(firstPath);
    try (first;
        var second = registry(secondPath)) {
      first.start();
      second.start();
      first.close();
      try (var replacement = registry(firstPath)) {
        replacement.start();
        first.close();
        try (var firstDuplicate = registry(firstPath);
            var secondDuplicate = registry(secondPath)) {
          assertThrows(IOException.class, firstDuplicate::start);
          assertThrows(IOException.class, secondDuplicate::start);
        }
      }
    }
  }

  @Test
  void failedOpenAndInvalidJournalReleaseLocalOwnershipAfterClosingDescriptor() throws IOException {
    Path root = directory.resolve("ownership");
    Path journal = Files.createDirectories(root.resolve("ownership.bin"));
    try (var failed = registry(root)) {
      assertThrows(IOException.class, failed::start);
    }
    Files.delete(journal);
    Files.writeString(journal, "invalid");
    try (var failed = registry(root)) {
      assertThrows(IOException.class, failed::start);
    }
    Files.delete(journal);
    try (var next = registry(root)) {
      next.start();
      assertEquals(0, next.outstanding());
    }
  }

  private static void assertChildAcquisition(Path root, boolean expected)
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
      try (var registry = registry(Path.of(args[0]))) {
        registry.start();
        System.out.println(registry.outstanding());
      } catch (IOException exception) {
        System.out.println(exception.getMessage());
        System.exit(3);
      }
    }
  }
}
