package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GatewayRuntimeStateInterruptionTest {
  @Test
  void interruptedDrainWaitReturnsFalseAndPreservesInterruptStatus() throws Exception {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    runtime.started();
    assertTrue(runtime.beginRequest());

    AtomicBoolean drained = new AtomicBoolean(true);
    AtomicBoolean interrupted = new AtomicBoolean();

    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  drained.set(runtime.awaitDrained(Duration.ofSeconds(5)));
                  interrupted.set(Thread.currentThread().isInterrupted());
                });

    awaitWaiting(waiter);
    waiter.interrupt();
    assertTrue(waiter.join(Duration.ofSeconds(2)));

    assertFalse(drained.get());
    assertTrue(interrupted.get());

    runtime.finishRequest();
    runtime.beginDrain();
    runtime.closed();
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() - deadline < 0) {
      Thread.State state = thread.getState();
      if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(1);
    }
    throw new AssertionError("drain waiter did not block");
  }
}
