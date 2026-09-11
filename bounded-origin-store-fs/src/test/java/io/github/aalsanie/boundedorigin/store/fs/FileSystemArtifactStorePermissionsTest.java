package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.artifact;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.bytes;
import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemArtifactStorePermissionsTest {
  @TempDir Path tempDirectory;

  @Test
  void readOnlyTemporaryDirectoryFailsWithoutPublishing() throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Assumptions.assumeTrue(!System.getProperty("user.name").equals("root"));
    Path root = tempDirectory.resolve("readonly");
    Set<PosixFilePermission> writable =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    Set<PosixFilePermission> readOnly =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 10_000, 1_000)) {
      Path tmp = root.resolve("tmp");
      Files.setPosixFilePermissions(tmp, readOnly);
      try {
        assertThrows(IOException.class, () -> store.put(key("x"), artifact(bytes(16, 1))));
      } finally {
        Files.setPosixFilePermissions(tmp, writable);
      }
    }
  }
}
