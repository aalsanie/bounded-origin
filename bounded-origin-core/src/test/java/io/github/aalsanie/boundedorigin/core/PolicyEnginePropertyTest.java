package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PolicyEnginePropertyTest {
  @Test
  void equivalentDimensionPermutationsProduceSameOperationKey() {
    OriginPolicy policy =
        OriginPolicy.materialize(
            "render",
            7,
            10,
            "renderer-3",
            Canonicalizers.byDimensions("id", "format", "size"),
            new Budget(2, 8, Duration.ofSeconds(3), 1024));
    PolicyRule rule =
        new PolicyRule(
            policy, request -> Optional.of(new Operation(request.name(), request.attributes())));
    PolicyEngine engine = new PolicyEngine(List.of(rule), OriginPolicy.deny("fallback", 1, -1));

    Map<String, List<String>> original = new LinkedHashMap<>();
    original.put("id", List.of("42"));
    original.put("format", List.of("webp"));
    original.put("size", List.of("large"));
    original.put("ignored", List.of("changes-do-not-matter"));
    RequestDescriptor baseline = new RequestDescriptor("render", original, TrustLevel.UNTRUSTED);
    OriginDecision.Selected expected =
        assertInstanceOf(OriginDecision.Selected.class, engine.evaluate(baseline));

    Random random = new Random(302991L);
    List<String> keys = new ArrayList<>(original.keySet());
    for (int iteration = 0; iteration < 5_000; iteration++) {
      Collections.shuffle(keys, random);
      Map<String, List<String>> permutation = new LinkedHashMap<>();
      for (String key : keys) {
        permutation.put(key, original.get(key));
      }
      permutation.put("ignored", List.of("ignored-" + iteration));
      OriginDecision.Selected actual =
          assertInstanceOf(
              OriginDecision.Selected.class,
              engine.evaluate(new RequestDescriptor("render", permutation, TrustLevel.UNTRUSTED)));
      assertEquals(expected.operationKey(), actual.operationKey());
    }
  }
}
