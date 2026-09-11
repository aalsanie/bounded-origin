package io.github.aalsanie.boundedorigin.proxy;

final class SpoolQuota {
  private final long maxBytes;
  private final int maxFiles;

  private long bytes;
  private int files;
  private boolean closed;

  SpoolQuota(long maxBytes, int maxFiles) {
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    if (maxFiles <= 0) {
      throw new IllegalArgumentException("maxFiles must be positive");
    }
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
  }

  synchronized Reservation openFile() {
    requireOpen();
    if (files >= maxFiles) {
      throw new SpoolLimitExceededException("temporary spool file limit reached");
    }
    files++;
    return new Reservation(this);
  }

  synchronized long bytes() {
    return bytes;
  }

  synchronized int files() {
    return files;
  }

  synchronized void close() {
    closed = true;
  }

  private synchronized void reserve(Reservation reservation, long count) {
    requireOwned(reservation);
    if (count < 0) {
      throw new IllegalArgumentException("count must be non-negative");
    }
    if (count > maxBytes - bytes) {
      throw new SpoolLimitExceededException("temporary spool byte limit reached");
    }
    bytes += count;
    reservation.bytes += count;
  }

  private synchronized void release(Reservation reservation) {
    requireOwned(reservation);
    if (reservation.released) {
      return;
    }
    reservation.released = true;
    if (reservation.bytes > bytes || files <= 0) {
      throw new IllegalStateException("temporary spool quota accounting underflow");
    }
    bytes -= reservation.bytes;
    files--;
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("temporary spool quota is closed");
    }
  }

  private void requireOwned(Reservation reservation) {
    if (reservation.owner != this) {
      throw new IllegalArgumentException("reservation belongs to a different quota");
    }
  }

  static final class Reservation implements AutoCloseable {
    private final SpoolQuota owner;
    private long bytes;
    private boolean released;

    private Reservation(SpoolQuota owner) {
      this.owner = owner;
    }

    void reserve(long count) {
      owner.reserve(this, count);
    }

    long bytes() {
      synchronized (owner) {
        return bytes;
      }
    }

    @Override
    public void close() {
      owner.release(this);
    }
  }

  static final class SpoolLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    SpoolLimitExceededException(String message) {
      super(message);
    }
  }
}
