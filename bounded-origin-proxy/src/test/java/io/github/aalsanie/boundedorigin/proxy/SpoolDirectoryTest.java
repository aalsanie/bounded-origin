package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpoolDirectoryTest {
  @TempDir Path directory;

  @Test
  void recoversOnlyOwnedFilesAndExcludesCompetingOwners() throws Exception {
    Path request = Files.write(directory.resolve("client-request-abandoned.tmp"), new byte[4]);
    Path response = Files.write(directory.resolve("origin-response-456.tmp"), new byte[8]);
    Path unrelated = Files.writeString(directory.resolve("unrelated.tmp"), "keep");
    Path nested = Files.createDirectory(directory.resolve("nested"));
    Path nestedFile = Files.writeString(nested.resolve("client-request-123.tmp"), "keep nested");
    SpoolDirectory owner = new SpoolDirectory(directory);
    try {
      assertFalse(Files.exists(request));
      assertFalse(Files.exists(response));
      assertEquals("keep", Files.readString(unrelated));
      assertEquals("keep nested", Files.readString(nestedFile));
      Files.write(request, new byte[] {1, 2});
      assertThrows(IOException.class, () -> new SpoolDirectory(directory.resolve(".")));
      assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(request));
    } finally {
      owner.close();
      owner.close();
    }
    SpoolDirectory restarted = new SpoolDirectory(directory);
    try (restarted) {
      assertFalse(Files.exists(request));
      assertThrows(IOException.class, () -> new SpoolDirectory(directory));
      assertEquals("keep", Files.readString(unrelated));
    }
  }

  @Test
  void failedRecoveryReleasesOwnershipAndNeverRecursivelyDeletes() throws Exception {
    Path corrupt = Files.createDirectory(directory.resolve("origin-response-99.tmp"));
    Path child = Files.writeString(corrupt.resolve("important"), "keep");
    assertThrows(IOException.class, () -> new SpoolDirectory(directory));
    assertEquals("keep", Files.readString(child));
    Files.delete(child);
    Files.delete(corrupt);
    SpoolDirectory owner = new SpoolDirectory(directory);
    try (owner) {
      assertThrows(IOException.class, () -> new SpoolDirectory(directory));
    }
  }

  @Test
  void failedLockOpenDoesNotLeaveAnInProcessOwner() throws Exception {
    Path lock = Files.createDirectory(directory.resolve(".bounded-origin-spool.lock"));
    assertThrows(IOException.class, () -> new SpoolDirectory(directory));
    Files.delete(lock);
    SpoolDirectory owner = new SpoolDirectory(directory);
    try (owner) {
      assertThrows(IOException.class, () -> new SpoolDirectory(directory));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void shutdownRetainsOwnershipThroughUnopenedResultsAndReaders(boolean openReader)
      throws Exception {
    SpoolDirectory owner = new SpoolDirectory(directory);
    SpoolQuota quota = new SpoolQuota(4, 1);
    StreamingSpool spool = new StreamingSpool(directory, "origin-response-", 4, quota);
    spool.append(new byte[] {1, 2, 3, 4}).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    InputStream reader = openReader ? result.openStream() : null;
    try {
      quota.closeWhenReleased(owner::close);
      assertThrows(IOException.class, () -> new SpoolDirectory(directory));
      assertThrows(IllegalStateException.class, quota::openFile);
      assertEquals(4, quota.bytes());
      spool.close();
      assertTrue(Files.exists(result.path()));
      result.close();
      if (reader != null) {
        assertThrows(IOException.class, () -> new SpoolDirectory(directory));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, reader.readAllBytes());
        reader.close();
      }
      assertEquals(0, quota.files());
      assertEquals(0, quota.bytes());
      assertFalse(Files.exists(result.path()));
      SpoolDirectory next = new SpoolDirectory(directory);
      try (next) {
        assertThrows(IOException.class, () -> new SpoolDirectory(directory));
      }
    } finally {
      if (reader != null) {
        reader.close();
      }
      result.close();
      owner.close();
    }
  }

  @Test
  void emptyQuotaCleansImmediatelyAndRejectsNewReservations() {
    SpoolQuota quota = new SpoolQuota(1, 1);
    AtomicInteger calls = new AtomicInteger();
    assertThrows(NullPointerException.class, () -> quota.closeWhenReleased(null));
    quota.closeWhenReleased(calls::incrementAndGet);
    assertEquals(1, calls.get());
    assertThrows(IllegalStateException.class, quota::openFile);
    quota.close();
    assertEquals(1, calls.get());
  }
}
