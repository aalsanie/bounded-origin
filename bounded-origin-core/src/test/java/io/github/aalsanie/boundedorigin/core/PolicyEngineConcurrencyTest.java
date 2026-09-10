package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyEngineConcurrencyTest {
  @Test
  void immutablePolicyEngineSupportsConcurrentEvaluation() throws InterruptedException {
    OriginPolicy selected =
        OriginPolicy.artifactOnly("selected", 1, 10, "m1", Canonicalizers.byDimensions("resource"));
    OriginPolicy fallback = OriginPolicy.deny("fallback", 1, Integer.MIN_VALUE);
    PolicyEngine engine =
        new PolicyEngine(
            List.of(
                new PolicyRule(
                    selected,
                    request ->
                        java.util.Optional.of(
                            new Operation(
                                request.name(),
                                Map.of(
                                    "resource",
                                    List.of(request.attributes().get("resource").getFirst())))))),
            fallback);

    int threads = 100;
    int iterations = 1_000;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    AtomicInteger failures = new AtomicInteger();

    for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
      int index = threadIndex;
      Thread.ofVirtual()
          .start(
              () -> {
                ready.countDown();
                ExecutionTestSupport.await(start);
                try {
                  for (int iteration = 0; iteration < iterations; iteration++) {
                    String value = index + "-" + iteration;
                    OriginDecision.Selected decision =
                        assertInstanceOf(
                            OriginDecision.Selected.class,
                            engine.evaluate(
                                new RequestDescriptor(
                                    "read",
                                    Map.of("resource", List.of(value)),
                                    TrustLevel.UNTRUSTED)));
                    if (!decision.operationKey().semanticIdentity().contains(value)) {
                      failures.incrementAndGet();
                    }
                  }
                } finally {
                  done.countDown();
                }
              });
    }

    ready.await();
    start.countDown();
    done.await();

    assertEquals(0, failures.get());
  }
}
