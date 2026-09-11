package io.github.aalsanie.boundedorigin.proxy;

import io.netty.channel.Channel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
      if (!accepting || closed || clients.size() >= maxClientConnections) {
        return false;
      }
      return clients.add(channel);
    }
  }

  void unregister(Channel channel) {
    synchronized (lock) {
      clients.remove(channel);
      lock.notifyAll();
    }
  }

  boolean beginRequest() {
    synchronized (lock) {
      if (!accepting || closed) {
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
      lock.notifyAll();
    }
  }

  boolean awaitDrained(Duration timeout) {
    long remainingNanos = timeout.toNanos();
    long deadline = System.nanoTime() + remainingNanos;
    synchronized (lock) {
      while (activeRequests != 0 && remainingNanos > 0) {
        long millis = remainingNanos / 1_000_000;
        int nanos = (int) (remainingNanos % 1_000_000);
        try {
          lock.wait(millis, nanos);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return false;
        }
        remainingNanos = deadline - System.nanoTime();
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
      return accepting && !closed;
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
      lock.notifyAll();
    }
  }
}
