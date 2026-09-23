package io.github.aalsanie.boundedorigin.core;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one caller's access to a shared result. Keep this handle open until all uses of the result
 * finish. Closing it does not cancel execution; the executor releases the body after completion and
 * the last caller's close. Callers must not close the shared body directly.
 */
public final class OriginExecution implements AutoCloseable {
  private final Shared shared;
  private final boolean joined;
  private final AtomicBoolean closed = new AtomicBoolean();

  private OriginExecution(Shared shared, boolean joined) {
    this.shared = shared;
    this.joined = joined;
  }

  public CompletionStage<Artifact> result() {
    return shared.stage;
  }

  /** Whether this caller joined a previously admitted operation. */
  public boolean joined() {
    return joined;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      shared.release();
    }
  }

  static void discard(Artifact artifact) {
    if (artifact == null) {
      return;
    }
    try {
      artifact.body().close();
    } catch (IOException | RuntimeException exception) {
      System.getLogger(OriginExecution.class.getName())
          .log(System.Logger.Level.WARNING, "failed to release origin result", exception);
    }
  }

  static final class Shared {
    final CompletableFuture<Artifact> future = new CompletableFuture<>();
    private final CompletionStage<Artifact> stage = future.minimalCompletionStage();
    private int owners;
    private Artifact artifact;

    Shared() {
      future.whenComplete((value, failure) -> completed(value));
    }

    synchronized OriginExecution acquire(boolean joined) {
      // Admission holds the executor lock: a removed job can never acquire another owner.
      owners++;
      return new OriginExecution(this, joined);
    }

    private void completed(Artifact value) {
      synchronized (this) {
        if (owners != 0) {
          artifact = value;
          return;
        }
      }
      discard(value);
    }

    private void release() {
      Artifact cleanup = null;
      synchronized (this) {
        owners--;
        if (owners == 0) {
          cleanup = artifact;
          artifact = null;
        }
      }
      discard(cleanup);
    }
  }
}
