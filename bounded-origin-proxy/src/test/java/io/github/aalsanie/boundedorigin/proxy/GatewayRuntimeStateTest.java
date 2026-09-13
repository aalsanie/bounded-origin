package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayRuntimeStateTest {
  @Test
  void lifecycleTracksConnectionsRequestsDrainAndClose() throws Exception {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    EmbeddedChannel first = new EmbeddedChannel();
    EmbeddedChannel second = new EmbeddedChannel();
    try {
      assertFalse(runtime.ready());
      assertTrue(runtime.healthy());
      runtime.started();
      assertTrue(runtime.ready());
      assertThrows(IllegalStateException.class, runtime::started);

      assertTrue(runtime.register(first));
      assertFalse(runtime.register(second));
      assertEquals(1, runtime.clientConnections());
      assertEquals(1, runtime.clients().size());

      assertTrue(runtime.beginRequest());
      assertEquals(1, runtime.activeRequests());
      assertFalse(runtime.awaitDrained(Duration.ofMillis(10)));

      Thread finisher =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      Thread.sleep(20);
                    } catch (InterruptedException exception) {
                      Thread.currentThread().interrupt();
                    }
                    runtime.finishRequest();
                  });
      assertTrue(runtime.awaitDrained(Duration.ofSeconds(1)));
      finisher.join();
      assertEquals(0, runtime.activeRequests());
      assertThrows(IllegalStateException.class, runtime::finishRequest);

      runtime.beginDrain();
      assertFalse(runtime.ready());
      assertTrue(runtime.healthy());
      assertTrue(runtime.draining());
      assertFalse(runtime.beginRequest());
      assertFalse(runtime.register(second));

      runtime.unregister(first);
      assertEquals(0, runtime.clientConnections());
      runtime.closed();
      assertFalse(runtime.healthy());
      assertFalse(runtime.draining());
      assertThrows(
          IllegalArgumentException.class, () -> runtime.awaitDrained(Duration.ofNanos(-1)));
    } finally {
      first.finishAndReleaseAll();
      second.finishAndReleaseAll();
    }
  }

  @Test
  void admissionResultsAreExactAcrossLifecycleAndCapacityBoundaries() {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    EmbeddedChannel first = new EmbeddedChannel();
    EmbeddedChannel second = new EmbeddedChannel();
    try {
      assertFalse(runtime.register(first));
      assertFalse(runtime.beginRequest());
      assertEquals(0, runtime.activeRequests());

      runtime.started();
      assertTrue(runtime.register(first));
      assertFalse(runtime.register(first));
      assertFalse(runtime.register(second));

      assertTrue(runtime.beginRequest());
      assertEquals(1, runtime.activeRequests());
      assertFalse(runtime.awaitDrained(Duration.ZERO));

      runtime.beginDrain();
      assertFalse(runtime.beginRequest());
      assertEquals(1, runtime.activeRequests());

      runtime.finishRequest();
      assertEquals(0, runtime.activeRequests());
      assertTrue(runtime.awaitDrained(Duration.ZERO));

      runtime.unregister(first);
      runtime.closed();
    } finally {
      first.finishAndReleaseAll();
      second.finishAndReleaseAll();
    }
  }

  @Test
  void constructorAndNullTimeoutAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new GatewayRuntimeState(0));
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    assertThrows(NullPointerException.class, () -> runtime.awaitDrained(null));
  }
}
