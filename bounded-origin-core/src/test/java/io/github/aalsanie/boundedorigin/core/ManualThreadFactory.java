package io.github.aalsanie.boundedorigin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;

final class ManualThreadFactory implements ThreadFactory {
  private final List<ManualThread> threads = new ArrayList<>();
  private final int failingStartIndex;
  private final Error startFailure;

  ManualThreadFactory() {
    this(-1, null);
  }

  ManualThreadFactory(int failingStartIndex, Error startFailure) {
    this.failingStartIndex = failingStartIndex;
    this.startFailure = startFailure;
  }

  @Override
  public synchronized Thread newThread(Runnable runnable) {
    int index = threads.size();
    Error failure = index == failingStartIndex ? startFailure : null;
    ManualThread thread = new ManualThread(runnable, failure);
    threads.add(thread);
    return thread;
  }

  synchronized int created() {
    return threads.size();
  }

  synchronized boolean started(int index) {
    return thread(index).started();
  }

  synchronized boolean interrupted(int index) {
    return thread(index).interruptedByExecutor();
  }

  void run(int index) {
    ManualThread thread;
    synchronized (this) {
      thread = thread(index);
    }
    thread.runTask();
  }

  private ManualThread thread(int index) {
    return threads.get(index);
  }

  private static final class ManualThread extends Thread {
    private final Runnable task;
    private final Error startFailure;
    private boolean started;
    private boolean interruptedByExecutor;

    private ManualThread(Runnable task, Error startFailure) {
      this.task = Objects.requireNonNull(task, "task");
      this.startFailure = startFailure;
    }

    @Override
    public synchronized void start() {
      started = true;
      if (startFailure != null) {
        throw startFailure;
      }
    }

    @Override
    public synchronized void interrupt() {
      interruptedByExecutor = true;
    }

    private synchronized boolean started() {
      return started;
    }

    private synchronized boolean interruptedByExecutor() {
      return interruptedByExecutor;
    }

    private void runTask() {
      task.run();
    }
  }
}
