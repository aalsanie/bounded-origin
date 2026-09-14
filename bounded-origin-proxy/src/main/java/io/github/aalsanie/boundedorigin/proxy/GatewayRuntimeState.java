package io.github.aalsanie.boundedorigin.proxy;

import io.netty.channel.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class GatewayRuntimeState {
  private final Object lock = new Object();
  private final int maxClientConnections;
  private final Set<Channel> clients = Collections.newSetFromMap(new IdentityHashMap<>());

  private boolean accepting;
  private boolean closed;
  private int activeRequests;

  GatewayRuntimeState(int maxClientConnections) {
    if (maxClientConnections <= 0) {
      throw new IllegalArgumentException("maxClientConnections must be positive");
    }
    this.maxClientConnections = maxClientConnections;
  }

  void started() {
    synchronized (lock) {
      if (closed || accepting) {
        throw new IllegalStateException("gateway lifecycle cannot be started");
      }
      accepting = true;
    }
  }

  boolean register(Channel channel) {
    synchronized (lock) {
      if (!accepting || clients.size() >= maxClientConnections) {
        return false;
      }
      return clients.add(channel);
    }
  }

  void unregister(Channel channel) {
    synchronized (lock) {
      clients.remove(channel);
    }
  }

  boolean beginRequest() {
    synchronized (lock) {
      if (!accepting) {
        return false;
      }
      activeRequests++;
      return true;
    }
  }

  void finishRequest() {
    synchronized (lock) {
      if (activeRequests <= 0) {
        throw new IllegalStateException("active request count underflow");
      }
      activeRequests--;
      lock.notifyAll();
    }
  }

  void beginDrain() {
    synchronized (lock) {
      accepting = false;
    }
  }

  boolean awaitDrained(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    long timeoutNanos = timeout.toNanos();
    if (timeoutNanos < 0) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    long started = System.nanoTime();
    synchronized (lock) {
      while (activeRequests != 0) {
        long elapsed = System.nanoTime() - started;
        long remainingNanos = timeoutNanos - elapsed;
        if (remainingNanos <= 0) {
          break;
        }
        long millis = remainingNanos / 1_000_000;
        int nanos = (int) (remainingNanos % 1_000_000);
        try {
          lock.wait(millis, nanos);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return activeRequests == 0;
    }
  }

  List<Channel> clients() {
    synchronized (lock) {
      return new ArrayList<>(clients);
    }
  }

  int clientConnections() {
    synchronized (lock) {
      return clients.size();
    }
  }

  int activeRequests() {
    synchronized (lock) {
      return activeRequests;
    }
  }

  boolean ready() {
    synchronized (lock) {
      return accepting;
    }
  }

  boolean healthy() {
    synchronized (lock) {
      return !closed;
    }
  }

  boolean draining() {
    synchronized (lock) {
      return !accepting && !closed;
    }
  }

  void closed() {
    synchronized (lock) {
      accepting = false;
      closed = true;
    }
  }
}
