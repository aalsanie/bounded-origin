package io.github.aalsanie.boundedorigin.proxy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

final class SpoolDirectory implements AutoCloseable {
  private static final Object OWNERS_LOCK = new Object();
  private static final Set<Path> OWNERS = ConcurrentHashMap.newKeySet();
  private static final Pattern SPOOL_NAME =
      Pattern.compile("(?:client-request-|origin-response-).*\\.tmp");

  private final Path directory;
  private final FileChannel channel;
  private boolean closed;

  SpoolDirectory(Path directory) throws IOException {
    Files.createDirectories(directory);
    this.directory = directory.toRealPath();
    // Closing a second descriptor can release a JVM's existing OS lock on some systems.
    reserveDirectory(this.directory);
    FileChannel opened = null;
    boolean acquired = false;
    try {
      opened =
          FileChannel.open(
              this.directory.resolve(".bounded-origin-spool.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      if (opened.tryLock() == null) {
        throw new IOException("temporary spool directory is already in use");
      }
      recover();
      channel = opened;
      acquired = true;
    } finally {
      if (!acquired) {
        if (opened != null) {
          opened.close();
        }
        OWNERS.remove(this.directory);
      }
    }
  }

  private static void reserveDirectory(Path directory) throws IOException {
    synchronized (OWNERS_LOCK) {
      for (Path owned : OWNERS) {
        // Bind mounts can name the same directory with different real paths.
        if (Files.isSameFile(owned, directory)) {
          throw new IOException("temporary spool directory is already in use");
        }
      }
      OWNERS.add(directory);
    }
  }

  private void recover() throws IOException {
    try (var entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        if (SPOOL_NAME.matcher(directory.relativize(entry).toString()).matches()) {
          if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("temporary spool recovery found a non-regular file");
          }
          Files.delete(entry);
        }
      }
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      channel.close();
      OWNERS.remove(directory);
    } catch (IOException exception) {
      System.getLogger(SpoolDirectory.class.getName())
          .log(
              System.Logger.Level.WARNING,
              "failed to release spool directory ownership",
              exception);
    }
  }
}
