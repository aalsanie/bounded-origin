package io.github.aalsanie.boundedorigin.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class StreamingSpool implements AutoCloseable {
  private final Path path;
  private final long maxBytes;
  private final FileChannel channel;
  private final MessageDigest digest;
  private final ExecutorService writer;
  private final SpoolQuota.Reservation reservation;

  private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
  private long acceptedBytes;
  private boolean finishing;
  private boolean cleanupScheduled;
  private boolean discarded;

  StreamingSpool(Path directory, String prefix, long maxBytes, SpoolQuota quota)
      throws IOException {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(quota, "quota");
    if (prefix.isBlank()) {
      throw new IllegalArgumentException("prefix must not be blank");
    }
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must be non-negative");
    }
    reservation = quota.openFile();
    boolean initialized = false;
    Path created = null;
    FileChannel opened = null;
    try {
      Files.createDirectories(directory);
      created = Files.createTempFile(directory, prefix, ".tmp");
      opened =
          FileChannel.open(created, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
      path = created;
      channel = opened;
      this.maxBytes = maxBytes;
      digest = MessageDigest.getInstance("SHA-256");
      writer =
          Executors.newSingleThreadExecutor(
              Thread.ofVirtual().name("bounded-origin-spool-", 0).factory());
      initialized = true;
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    } finally {
      if (!initialized) {
        if (opened != null) {
          try {
            opened.close();
          } catch (IOException ignored) {
          }
        }
        if (created != null) {
          try {
            Files.deleteIfExists(created);
          } catch (IOException ignored) {
          }
        }
        reservation.close();
      }
    }
  }

  synchronized CompletionStage<Void> append(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    ensureWritable();
    if (bytes.length > maxBytes - acceptedBytes) {
      throw new BodyLimitExceededException(maxBytes);
    }
    try {
      reservation.reserve(bytes.length);
    } catch (SpoolQuota.SpoolLimitExceededException exception) {
      throw new SpoolCapacityExceededException(exception);
    }
    acceptedBytes += bytes.length;
    byte[] owned = bytes.clone();
    tail = tail.thenRunAsync(() -> write(owned), writer);
    return tail;
  }

  synchronized CompletionStage<Result> finish() {
    ensureWritable();
    finishing = true;
    long finalLength = acceptedBytes;
    CompletableFuture<Void> completion = tail;
    return completion.handle(
        (ignored, failure) -> {
          IOException closeFailure = closeChannel();
          writer.shutdown();
          if (discarded) {
            cleanupNow();
            throw new CompletionException(new IOException("spool was discarded"));
          }
          if (failure != null) {
            cleanupNow();
            throw asCompletionException(failure);
          }
          if (closeFailure != null) {
            cleanupNow();
            throw new CompletionException(closeFailure);
          }
          return new Result(
              path, finalLength, HexFormat.of().formatHex(digest.digest()), reservation);
        });
  }

  synchronized long acceptedBytes() {
    return acceptedBytes;
  }

  @Override
  public synchronized void close() {
    if (cleanupScheduled) {
      return;
    }
    finishing = true;
    discarded = true;
    cleanupScheduled = true;
    CompletableFuture<Void> completion = tail;
    completion.whenComplete(
        (ignored, failure) -> {
          closeChannel();
          writer.shutdown();
          cleanupNow();
        });
  }

  private void write(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    try {
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      digest.update(bytes);
    } catch (IOException exception) {
      throw new CompletionException(exception);
    }
  }

  private synchronized IOException closeChannel() {
    if (!channel.isOpen()) {
      return null;
    }
    try {
      channel.close();
      return null;
    } catch (IOException exception) {
      return exception;
    }
  }

  private void ensureWritable() {
    if (finishing) {
      throw new IllegalStateException("spool is not writable");
    }
  }

  private void cleanupNow() {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      System.getLogger(StreamingSpool.class.getName())
          .log(System.Logger.Level.WARNING, "failed to delete temporary spool", exception);
    } finally {
      reservation.close();
    }
  }

  private static CompletionException asCompletionException(Throwable throwable) {
    Throwable current = throwable;
    while (current instanceof CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return new CompletionException(current);
  }

  static final class Result implements AutoCloseable {
    private final Path path;
    private final long length;
    private final String sha256;
    private final SpoolQuota.Reservation reservation;
    private final AtomicBoolean released = new AtomicBoolean();

    Result(Path path, long length, String sha256, SpoolQuota.Reservation reservation) {
      this.path = Objects.requireNonNull(path, "path");
      this.sha256 = Objects.requireNonNull(sha256, "sha256");
      this.reservation = Objects.requireNonNull(reservation, "reservation");
      if (length < 0) {
        throw new IllegalArgumentException("length must be non-negative");
      }
      this.length = length;
    }

    Path path() {
      return path;
    }

    long length() {
      return length;
    }

    String sha256() {
      return sha256;
    }

    InputStream openStream() throws IOException {
      if (released.get()) {
        throw new IOException("temporary spool was released");
      }
      return Files.newInputStream(path);
    }

    boolean released() {
      return released.get();
    }

    @Override
    public void close() {
      if (!released.compareAndSet(false, true)) {
        return;
      }
      try {
        Files.deleteIfExists(path);
      } catch (IOException exception) {
        System.getLogger(StreamingSpool.class.getName())
            .log(System.Logger.Level.WARNING, "failed to delete completed spool", exception);
      } finally {
        reservation.close();
      }
    }
  }

  static final class SpoolCapacityExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SpoolCapacityExceededException(Throwable cause) {
      super("temporary spool capacity is exhausted", cause);
    }
  }

  static final class BodyLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long limit;

    BodyLimitExceededException(long limit) {
      super("body exceeds configured limit " + limit);
      this.limit = limit;
    }

    long limit() {
      return limit;
    }
  }
}
