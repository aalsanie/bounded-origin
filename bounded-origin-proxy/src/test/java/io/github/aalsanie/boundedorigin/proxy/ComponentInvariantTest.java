package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class ComponentInvariantTest {
  @Test
  void runtimeRejectsWorkBeforeStartAtCapacityAndAfterClose() {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    EmbeddedChannel first = new EmbeddedChannel();
    EmbeddedChannel second = new EmbeddedChannel();
    try {
      assertFalse(runtime.register(first));
      assertFalse(runtime.beginRequest());
      assertFalse(runtime.ready());

      runtime.started();
      assertTrue(runtime.ready());
      assertTrue(runtime.register(first));
      assertFalse(runtime.register(second));
      assertTrue(runtime.beginRequest());
      runtime.finishRequest();
      assertThrows(IllegalStateException.class, runtime::started);

      runtime.closed();
      assertFalse(runtime.ready());
      assertFalse(runtime.register(second));
      assertFalse(runtime.beginRequest());
      assertThrows(IllegalStateException.class, runtime::started);
    } finally {
      runtime.unregister(first);
      runtime.unregister(second);
      first.finishAndReleaseAll();
      second.finishAndReleaseAll();
    }
  }

  @Test
  void runtimeDrainRejectsNewRequestsButRemainsHealthyUntilClosed() {
    GatewayRuntimeState runtime = new GatewayRuntimeState(1);
    runtime.started();
    assertTrue(runtime.healthy());
    runtime.beginDrain();
    assertTrue(runtime.draining());
    assertFalse(runtime.beginRequest());
    assertTrue(runtime.healthy());
    runtime.closed();
    assertFalse(runtime.draining());
    assertFalse(runtime.healthy());
  }
}
