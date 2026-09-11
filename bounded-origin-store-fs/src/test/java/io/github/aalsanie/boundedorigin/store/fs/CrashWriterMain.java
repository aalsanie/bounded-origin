package io.github.aalsanie.boundedorigin.store.fs;

import static io.github.aalsanie.boundedorigin.store.fs.StoreTestSupport.key;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CrashWriterMain {
  private CrashWriterMain() {}

  public static void main(String[] args) throws IOException {
    Path root = Path.of(args[0]);
    Path marker = Path.of(args[1]);
    AtomicBoolean emitted = new AtomicBoolean();
    Artifact artifact =
        new Artifact(
            8_192,
            Map.of(),
            () ->
                new InputStream() {
                  @Override
                  public int read() {
                    throw new AssertionError("bulk reads expected");
                  }

                  @Override
                  public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (emitted.compareAndSet(false, true)) {
                      int count = Math.min(1_024, length);
                      Arrays.fill(buffer, offset, offset + count, (byte) 4);
                      Files.writeString(marker, "staging");
                      return count;
                    }
                    try {
                      Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                      throw new IOException("interrupted", exception);
                    }
                    return -1;
                  }
                });

    try (FileSystemArtifactStore store = new FileSystemArtifactStore(root, 1_000_000, 100_000)) {
      store.put(key("crash"), artifact);
    }
  }
}
