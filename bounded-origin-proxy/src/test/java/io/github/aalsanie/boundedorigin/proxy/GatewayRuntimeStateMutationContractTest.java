package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GatewayRuntimeStateMutationContractTest {
  @Test
  void registrationAndRequestAdmissionReturnValuesArePartOfTheContract() {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    EmbeddedChannel first = new EmbeddedChannel();
    EmbeddedChannel second = new EmbeddedChannel();
    try {
      assertFalse(runtime.register(first));
      assertFalse(runtime.beginRequest());

      runtime.started();
      assertTrue(runtime.register(first));
      assertFalse(runtime.register(second));
      assertTrue(runtime.beginRequest());
      runtime.finishRequest();

      runtime.beginDrain();
      assertFalse(runtime.beginRequest());
      runtime.closed();
      assertFalse(runtime.register(second));
    } finally {
      runtime.unregister(first);
      runtime.unregister(second);
      first.finishAndReleaseAll();
      second.finishAndReleaseAll();
    }
  }

  @Test
  void zeroTimeoutDistinguishesAlreadyDrainedFromActiveWork() {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    runtime.started();
    assertTrue(runtime.awaitDrained(Duration.ZERO));

    assertTrue(runtime.beginRequest());
    assertFalse(runtime.awaitDrained(Duration.ZERO));
    runtime.finishRequest();
    assertTrue(runtime.awaitDrained(Duration.ZERO));

    assertThrows(IllegalArgumentException.class, () -> runtime.awaitDrained(Duration.ofNanos(-1)));
    runtime.beginDrain();
    runtime.closed();
  }

  @Test
  void finishingTheLastRequestWakesDrainWaitersPromptly() throws Exception {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    runtime.started();
    assertTrue(runtime.beginRequest());

    AtomicBoolean drained = new AtomicBoolean();
    AtomicLong elapsedNanos = new AtomicLong();
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  long started = System.nanoTime();
                  drained.set(runtime.awaitDrained(Duration.ofSeconds(2)));
                  elapsedNanos.set(System.nanoTime() - started);
                });

    awaitWaiting(waiter);
    runtime.finishRequest();
    assertTrue(waiter.join(Duration.ofSeconds(1)));
    assertTrue(drained.get());
    assertTrue(elapsedNanos.get() < Duration.ofSeconds(1).toNanos());

    runtime.beginDrain();
    runtime.closed();
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
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
