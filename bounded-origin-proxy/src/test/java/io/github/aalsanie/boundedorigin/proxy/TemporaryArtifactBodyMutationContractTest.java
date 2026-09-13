package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryArtifactBodyMutationContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void deleteReleasesTheUnderlyingSpoolExactlyOnce() throws Exception {
    SpoolQuota quota = new SpoolQuota(32, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "artifact-", 32, quota);
    spool.append(new byte[] {1, 2, 3}).toCompletableFuture().join();
    StreamingSpool.Result result = spool.finish().toCompletableFuture().join();
    TemporaryArtifactBody body = new TemporaryArtifactBody(result);

    assertFalse(body.deleted());
    assertTrue(Files.exists(result.path()));
    assertEquals(3, quota.bytes());
    assertEquals(1, quota.files());

    body.delete();

    assertTrue(body.deleted());
    assertTrue(result.released());
    assertFalse(Files.exists(result.path()));
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
    assertThrows(IOException.class, body::openStream);

    body.delete();
    assertTrue(result.released());
    assertEquals(0, quota.bytes());
    assertEquals(0, quota.files());
  }
}
