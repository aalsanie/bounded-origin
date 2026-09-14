package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GatewayRuntimeStateBoundaryTest {
  @Test
  void duplicateConnectionRegistrationIsRejectedWithoutConsumingCapacity() {
    GatewayRuntimeState runtime = new GatewayRuntimeState(2);
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      runtime.started();
      assertTrue(runtime.register(channel));
      assertFalse(runtime.register(channel));
      assertEquals(1, runtime.clientConnections());
    } finally {
      runtime.unregister(channel);
      channel.finishAndReleaseAll();
    }
  }

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
                  drained.set(runtime.awaitDrained(Duration.ofSeconds(10)));
                  interrupted.set(Thread.currentThread().isInterrupted());
                });
    waitUntilBlocked(waiter);
    waiter.interrupt();
    waiter.join();

    assertFalse(drained.get());
    assertTrue(interrupted.get());
    runtime.finishRequest();
  }

  private static void waitUntilBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
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
