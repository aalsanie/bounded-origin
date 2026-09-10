package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginDecisionTest {
  @Test
  void selectedRequiresExecutablePolicyAndValues() {
    OriginPolicy policy = OriginPolicy.artifactOnly("p", 1, 1, "m", Canonicalizers.byDimensions());
    Operation operation = new Operation("op", Map.of());
    OperationKey key = new OperationKey("p", 1, "2:op", "m");

    OriginDecision.Selected selected = new OriginDecision.Selected(policy, operation, key);
    assertEquals(policy, selected.policy());
    assertEquals(operation, selected.operation());
    assertEquals(key, selected.operationKey());
    assertThrows(
        NullPointerException.class, () -> new OriginDecision.Selected(null, operation, key));
    assertThrows(NullPointerException.class, () -> new OriginDecision.Selected(policy, null, key));
    assertThrows(
        NullPointerException.class, () -> new OriginDecision.Selected(policy, operation, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OriginDecision.Selected(OriginPolicy.deny("d", 0, 0), operation, key));
  }

  @Test
  void selectedRejectsKeyThatDoesNotMatchPolicyIdentity() {
    OriginPolicy policy = OriginPolicy.artifactOnly("p", 2, 1, "m2", Canonicalizers.byDimensions());
    Operation operation = new Operation("op", Map.of());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OriginDecision.Selected(
                policy, operation, new OperationKey("other", 2, "identity", "m2")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OriginDecision.Selected(
                policy, operation, new OperationKey("p", 3, "identity", "m2")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OriginDecision.Selected(
                policy, operation, new OperationKey("p", 2, "identity", "other")));
  }

  @Test
  void deniedCopiesAndValidatesPolicyIds() {
    List<String> ids = new ArrayList<>(List.of("a", "b"));
    OriginDecision.Denied denied = new OriginDecision.Denied(DenialReason.AMBIGUOUS_POLICY, ids);
    ids.add("c");

    assertEquals(List.of("a", "b"), denied.policyIds());
    assertThrows(UnsupportedOperationException.class, () -> denied.policyIds().add("c"));
    assertThrows(NullPointerException.class, () -> new OriginDecision.Denied(null, List.of("a")));
    assertThrows(
        NullPointerException.class, () -> new OriginDecision.Denied(DenialReason.NO_MATCH, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OriginDecision.Denied(DenialReason.NO_MATCH, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OriginDecision.Denied(DenialReason.NO_MATCH, List.of(" ")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OriginDecision.Denied(DenialReason.NO_MATCH, List.of("a", "a")));
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(
        NullPointerException.class,
        () -> new OriginDecision.Denied(DenialReason.NO_MATCH, withNull));
  }
}
