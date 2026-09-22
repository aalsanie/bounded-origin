package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginWorkRegistryTest {
  @TempDir Path directory;

  @Test
  void capacityRemainsOwnedUntilConfirmedCompletion() throws IOException {
    GatewayMetrics metrics = new GatewayMetrics();
    try (OriginWorkRegistry registry = registry(2, metrics)) {
      registry.start();
      assertMetric(metrics, "origin_work_registry_available", 1);
      var first = registry.reserve(key("p", "one"), 1);
      assertEquals(1, registry.outstanding());
      assertEquals(0, registry.unresolved());
      assertMetric(metrics, "origin_work_outstanding", 1);
      assertMetric(metrics, "origin_work_unresolved", 0);
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> registry.reserve(key("p", "one"), 2));
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> registry.reserve(key("p", "two"), 1));
      var second = registry.reserve(key("q", "two"), 1);
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> registry.reserve(key("r", "three"), 1));
      first.close();
      first.close();
      assertEquals(2, registry.outstanding());
      assertEquals(1, registry.unresolved());
      assertMetric(metrics, "origin_work_outstanding", 2);
      assertMetric(metrics, "origin_work_unresolved", 1);
      assertMetric(metrics, "origin_work_registry_available", 1);
      first.completed();
      assertEquals(1, registry.outstanding());
      assertEquals(0, registry.unresolved());
      assertMetric(metrics, "origin_work_outstanding", 1);
      assertMetric(metrics, "origin_work_unresolved", 0);
      var replacement = registry.reserve(key("p", "one"), 1);
      first.completed();
      first.close();
      assertEquals(2, registry.outstanding());
      assertEquals(0, registry.unresolved());
      replacement.completed();
      second.completed();
      assertEquals(0, registry.outstanding());
    }
    assertMetric(metrics, "origin_work_registry_available", 0);
    try (OriginWorkRegistry restored = registry(2, new GatewayMetrics())) {
      restored.start();
      assertEquals(0, restored.outstanding());
    }
  }

  @Test
  void emptyRegistryPersistsBeforeAnyWorkIsAdmitted() throws IOException {
    try (OriginWorkRegistry registry = registry(1, new GatewayMetrics())) {
      registry.start();
      assertEquals(0, registry.outstanding());
    }
    try (OriginWorkRegistry restored = registry(1, new GatewayMetrics())) {
      restored.start();
      assertEquals(0, restored.outstanding());
      assertEquals(0, restored.unresolved());
      var first = restored.reserve(key("p", "one"), 1);
      first.completed();
      assertEquals(0, restored.outstanding());
    }
  }

  @Test
  void restartRestoresUnknownOwnershipAndOldCallbacksCannotReleaseIt() throws IOException {
    OriginWorkRegistry.Permit old;
    OriginWorkRegistry registry = registry(1, new GatewayMetrics());
    try (registry) {
      registry.start();
      old = registry.reserve(key("p", "one"), 1);
      assertEquals(0, registry.unresolved());
    }
    assertEquals(1, registry.outstanding());
    assertEquals(1, registry.unresolved());
    try (OriginWorkRegistry restored = registry(1, new GatewayMetrics())) {
      restored.start();
      assertEquals(1, restored.outstanding());
      assertEquals(1, restored.unresolved());
      old.completed();
      old.close();
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> restored.reserve(key("p", "one"), 1));
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> restored.reserve(key("q", "distinct"), 1));
      assertEquals(1, restored.outstanding());
    }
  }

  @Test
  void directoryCannotHaveTwoOwners() throws IOException {
    try (OriginWorkRegistry first = registry(1, new GatewayMetrics());
        OriginWorkRegistry second = registry(1, new GatewayMetrics())) {
      first.start();
      assertThrows(IOException.class, second::start);
      var permit = first.reserve(key("p", "one"), 1);
      permit.completed();
      assertEquals(0, first.outstanding());
    }
    try (OriginWorkRegistry next = registry(1, new GatewayMetrics())) {
      next.start();
      assertEquals(0, next.outstanding());
    }
  }

  @Test
  void disabledUnstartedClosedAndInvalidAdmissionsCannotDispatch() throws IOException {
    GatewayConfig config =
        GatewayTestFixtures.config(
            8080, directory, Map.of("origin.completion-contract", "DISABLED"));
    try (OriginWorkRegistry disabled = new OriginWorkRegistry(config, new GatewayMetrics())) {
      disabled.start();
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> disabled.reserve(key("p", "one"), 1));
      assertThrows(IllegalStateException.class, disabled::start);
      assertTrue(Files.notExists(directory.resolve("ownership")));
    }
    OriginWorkRegistry registry = registry(1, new GatewayMetrics());
    assertThrows(
        OriginWorkRegistry.UnavailableException.class, () -> registry.reserve(key("p", "one"), 1));
    assertThrows(NullPointerException.class, () -> registry.reserve(null, 1));
    assertThrows(IllegalArgumentException.class, () -> registry.reserve(key("p", "one"), 0));
    registry.close();
    assertThrows(IllegalStateException.class, registry::start);
    assertThrows(
        OriginWorkRegistry.UnavailableException.class, () -> registry.reserve(key("p", "one"), 1));
  }

  @Test
  void persistenceFailureClosesAdmissionWithoutForgettingExistingWork() throws Exception {
    GatewayMetrics metrics = new GatewayMetrics();
    try (OriginWorkRegistry registry = registry(2, metrics)) {
      registry.start();
      var first = registry.reserve(key("p", "one"), 2);
      var channel = OriginWorkRegistry.class.getDeclaredField("channel");
      channel.setAccessible(true);
      ((FileChannel) channel.get(registry)).close();
      assertThrows(OriginWorkRegistry.UnavailableException.class, first::completed);
      first.completed();
      assertEquals(1, registry.outstanding());
      assertEquals(1, registry.unresolved());
      assertMetric(metrics, "origin_work_registry_available", 0);
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> registry.reserve(key("p", "two"), 2));
    }
    try (OriginWorkRegistry restored = registry(2, new GatewayMetrics())) {
      restored.start();
      assertEquals(1, restored.outstanding());
    }
  }

  @Test
  void eachOperationKeyDimensionSeparatesIdentity() throws IOException {
    try (OriginWorkRegistry registry = registry(7, new GatewayMetrics())) {
      registry.start();
      registry.reserve(new OperationKey("ab", 1, "c", "v"), 6);
      registry.reserve(new OperationKey("a", 1, "bc", "v"), 6);
      registry.reserve(new OperationKey("ab", 2, "c", "v"), 6);
      registry.reserve(new OperationKey("ab", 1, "d", "v"), 6);
      registry.reserve(new OperationKey("ab", 1, "c", "v2"), 6);
      registry.reserve(new OperationKey("p1", 2, "x", "v"), 6);
      registry.reserve(new OperationKey("p", 12, "x", "v"), 6);
      assertEquals(7, registry.outstanding());
    }
  }

  @Test
  void endpointChangeOrReducedGlobalCapacityFailsStartup() throws IOException {
    try (OriginWorkRegistry registry = registry(2, new GatewayMetrics())) {
      registry.start();
      registry.reserve(key("p", "one"), 2);
      registry.reserve(key("p", "two"), 2);
    }
    try (OriginWorkRegistry reduced = registry(1, new GatewayMetrics())) {
      assertThrows(IOException.class, reduced::start);
    }
    GatewayConfig changed = GatewayTestFixtures.config(8081, directory);
    try (OriginWorkRegistry different = new OriginWorkRegistry(changed, new GatewayMetrics())) {
      assertThrows(IOException.class, different::start);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void corruptTruncatedUnsupportedAndDuplicateJournalsFailClosed(int variant) throws Exception {
    try (OriginWorkRegistry registry = registry(2, new GatewayMetrics())) {
      registry.start();
      registry.reserve(key("p", "one"), 2);
      registry.reserve(key("p", "two"), 2);
    }
    Path file = directory.resolve("ownership/ownership.bin");
    byte[] bytes = Files.readAllBytes(file);
    switch (variant) {
      case 0 -> bytes = Arrays.copyOf(bytes, 10);
      case 1 -> bytes[41] ^= 1;
      case 2 -> {
        ByteBuffer.wrap(bytes).putInt(123);
        checksum(bytes);
      }
      case 3 -> {
        ByteBuffer.wrap(bytes).putInt(36, -1);
        checksum(bytes);
      }
      case 4 -> {
        ByteBuffer.wrap(bytes).putInt(36, 1);
        checksum(bytes);
      }
      case 5 -> {
        System.arraycopy(bytes, 40, bytes, 104, 64);
        checksum(bytes);
      }
      default -> throw new AssertionError(variant);
    }
    Files.write(file, bytes);
    try (OriginWorkRegistry restored = registry(2, new GatewayMetrics())) {
      assertThrows(IOException.class, restored::start);
      var channel = OriginWorkRegistry.class.getDeclaredField("channel");
      channel.setAccessible(true);
      assertFalse(((FileChannel) channel.get(restored)).isOpen());
      assertThrows(
          OriginWorkRegistry.UnavailableException.class,
          () -> restored.reserve(key("p", "three"), 2));
    }
  }

  @Test
  void invalidDirectoryCannotStart() throws IOException {
    Files.writeString(directory.resolve("ownership"), "not a directory");
    try (OriginWorkRegistry registry = registry(1, new GatewayMetrics())) {
      assertThrows(IOException.class, registry::start);
    }
  }

  private OriginWorkRegistry registry(int active, GatewayMetrics metrics) {
    return new OriginWorkRegistry(
        GatewayTestFixtures.config(
            8080, directory, Map.of("origin.max-active", Integer.toString(active))),
        metrics);
  }

  private static OperationKey key(String policy, String semantic) {
    return new OperationKey(policy, 1, semantic, "v1");
  }

  private static void checksum(byte[] bytes) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
    System.arraycopy(digest, 0, bytes, bytes.length - 32, 32);
  }

  private static void assertMetric(GatewayMetrics metrics, String name, int value) {
    assertTrue(
        metrics
            .prometheus(new OriginExecutorStats(0, 0, 0, 0), 0, 0, 0, 0, 0, 0)
            .contains("bounded_origin_" + name + " " + value + "\n"));
  }
}
