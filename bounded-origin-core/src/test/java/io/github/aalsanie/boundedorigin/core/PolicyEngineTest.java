package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.DenialReason;
import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.PolicyMatcher;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {
  private static final RequestDescriptor REQUEST =
      new RequestDescriptor("render", Map.of("id", List.of("42")), TrustLevel.UNTRUSTED);
  private static final Budget BUDGET = new Budget(2, 4, Duration.ofSeconds(1), 1024);

  @Test
  void choosesHighestPrecedenceIndependentOfRegistrationOrder() {
    PolicyRule low = rule(materialize("low", 1));
    PolicyRule high = rule(materialize("high", 8));
    PolicyRule lower = rule(materialize("lower", -2));

    OriginDecision first =
        new PolicyEngine(List.of(low, high, lower), fallback()).evaluate(REQUEST);
    OriginDecision second =
        new PolicyEngine(List.of(lower, high, low), fallback()).evaluate(REQUEST);

    assertEquals("high", assertInstanceOf(OriginDecision.Selected.class, first).policy().id());
    assertEquals(
        assertInstanceOf(OriginDecision.Selected.class, first).operationKey(),
        assertInstanceOf(OriginDecision.Selected.class, second).operationKey());
  }

  @Test
  void higherPrecedenceReplacesLowerMatchedOperation() {
    PolicyRule low =
        new PolicyRule(
            materialize("low", 1),
            request -> Optional.of(new Operation("low-operation", Map.of("id", List.of("low")))));
    PolicyRule high =
        new PolicyRule(
            materialize("high", 2),
            request -> Optional.of(new Operation("high-operation", Map.of("id", List.of("high")))));

    OriginDecision.Selected selected =
        assertInstanceOf(
            OriginDecision.Selected.class,
            new PolicyEngine(List.of(low, high), fallback()).evaluate(REQUEST));

    assertEquals("high-operation", selected.operation().type());
    assertEquals(List.of("high"), selected.operation().dimensions().get("id"));
  }

  @Test
  void equalHighestPrecedenceFailsClosedWithSortedCandidates() {
    PolicyRule z = rule(materialize("z", 10));
    PolicyRule a = rule(materialize("a", 10));
    PolicyRule lower = rule(materialize("lower", 1));

    OriginDecision.Denied denied =
        assertInstanceOf(
            OriginDecision.Denied.class,
            new PolicyEngine(List.of(z, lower, a), fallback()).evaluate(REQUEST));

    assertEquals(DenialReason.AMBIGUOUS_POLICY, denied.reason());
    assertEquals(List.of("a", "z"), denied.policyIds());
  }

  @Test
  void explicitFallbackDeniesUnknownOperations() {
    PolicyRule noMatch = new PolicyRule(materialize("render", 1), request -> Optional.empty());
    OriginDecision.Denied denied =
        assertInstanceOf(
            OriginDecision.Denied.class,
            new PolicyEngine(List.of(noMatch), fallback()).evaluate(REQUEST));

    assertEquals(DenialReason.NO_MATCH, denied.reason());
    assertEquals(List.of("fallback"), denied.policyIds());
  }

  @Test
  void denyStrategyNeverProducesOperationKey() {
    PolicyRule deny =
        new PolicyRule(
            OriginPolicy.deny("blocked", 2, 100),
            request -> Optional.of(new Operation("blocked", Map.of())));

    OriginDecision.Denied decision =
        assertInstanceOf(
            OriginDecision.Denied.class,
            new PolicyEngine(List.of(deny), fallback()).evaluate(REQUEST));

    assertEquals(DenialReason.POLICY_DENIED, decision.reason());
    assertEquals(List.of("blocked"), decision.policyIds());
  }

  @Test
  void policyAndMaterializerVersionsInvalidateKeys() {
    OriginDecision.Selected v1 = selected(materialize("p", 1, "m1"));
    OriginDecision.Selected v2 = selected(materialize("p", 2, "m1"));
    OriginDecision.Selected m2 = selected(materialize("p", 1, "m2"));

    assertEquals("p", v1.operationKey().policyId());
    org.junit.jupiter.api.Assertions.assertNotEquals(v1.operationKey(), v2.operationKey());
    org.junit.jupiter.api.Assertions.assertNotEquals(v1.operationKey(), m2.operationKey());
  }

  @Test
  void classifierAndCanonicalizerFailuresFailClosed() {
    PolicyRule throwsClassifier =
        new PolicyRule(
            materialize("classify", 1),
            request -> {
              throw new IllegalStateException("broken");
            });
    PolicyRule nullClassifier = new PolicyRule(materialize("null", 2), nullReturningMatcher());
    OriginPolicy throwingCanonicalizer =
        OriginPolicy.materialize(
            "canonical",
            1,
            3,
            "m1",
            operation -> {
              throw new IllegalStateException("broken");
            },
            BUDGET);
    OriginPolicy nullCanonicalizer =
        OriginPolicy.materialize("null-canonical", 1, 4, "m1", operation -> null, BUDGET);
    OriginPolicy blankCanonicalizer =
        OriginPolicy.materialize("blank-canonical", 1, 5, "m1", operation -> " ", BUDGET);

    assertPolicyError(
        new PolicyEngine(List.of(throwsClassifier), fallback()).evaluate(REQUEST), "classify");
    assertPolicyError(
        new PolicyEngine(List.of(nullClassifier), fallback()).evaluate(REQUEST), "null");
    assertPolicyError(
        new PolicyEngine(List.of(rule(throwingCanonicalizer)), fallback()).evaluate(REQUEST),
        "canonical");
    assertPolicyError(
        new PolicyEngine(List.of(rule(nullCanonicalizer)), fallback()).evaluate(REQUEST),
        "null-canonical");
    assertPolicyError(
        new PolicyEngine(List.of(rule(blankCanonicalizer)), fallback()).evaluate(REQUEST),
        "blank-canonical");
  }

  @Test
  void validatesPolicyTable() {
    PolicyRule rule = rule(materialize("p", 1));
    assertThrows(NullPointerException.class, () -> new PolicyEngine(null, fallback()));
    assertThrows(NullPointerException.class, () -> new PolicyEngine(List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyEngine(List.of(), materialize("unsafe-fallback", 0)));
    assertThrows(
        IllegalArgumentException.class, () -> new PolicyEngine(List.of(rule, rule), fallback()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyEngine(List.of(rule(OriginPolicy.deny("fallback", 0, 0))), fallback()));
    List<PolicyRule> withNull = new ArrayList<>();
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> new PolicyEngine(withNull, fallback()));
    PolicyEngine engine = new PolicyEngine(List.of(), fallback());
    assertThrows(NullPointerException.class, () -> engine.evaluate(null));
  }

  @Test
  void supportsAllExecutableStrategies() {
    List<OriginPolicy> policies =
        List.of(
            OriginPolicy.artifactOnly("artifact", 1, 1, "m", Canonicalizers.byDimensions("id")),
            OriginPolicy.boundedCompute(
                "bounded", 1, 1, "m", Canonicalizers.byDimensions("id"), BUDGET),
            OriginPolicy.materialize(
                "materialize", 1, 1, "m", Canonicalizers.byDimensions("id"), BUDGET),
            OriginPolicy.clientCompute(
                "client",
                1,
                1,
                "m",
                Canonicalizers.byDimensions("id"),
                new io.github.aalsanie.boundedorigin.api.ClientComputation(
                    "client", "v1", Map.of())));

    for (OriginPolicy policy : policies) {
      OriginDecision.Selected selected = selected(policy);
      assertEquals(policy.strategy(), selected.policy().strategy());
    }
    assertEquals(
        4,
        policies.stream()
            .map(OriginPolicy::strategy)
            .filter(s -> s != ExecutionStrategy.DENY)
            .count());
  }

  private static OriginDecision.Selected selected(OriginPolicy policy) {
    return assertInstanceOf(
        OriginDecision.Selected.class,
        new PolicyEngine(List.of(rule(policy)), fallback()).evaluate(REQUEST));
  }

  private static void assertPolicyError(OriginDecision decision, String policyId) {
    OriginDecision.Denied denied = assertInstanceOf(OriginDecision.Denied.class, decision);
    assertEquals(DenialReason.POLICY_ERROR, denied.reason());
    assertEquals(List.of(policyId), denied.policyIds());
  }

  private static PolicyRule rule(OriginPolicy policy) {
    return new PolicyRule(
        policy,
        request ->
            Optional.of(
                new Operation(request.name(), Map.of("id", request.attributes().get("id")))));
  }

  private static OriginPolicy materialize(String id, int precedence) {
    return OriginPolicy.materialize(
        id, 1, precedence, "m1", Canonicalizers.byDimensions("id"), BUDGET);
  }

  private static OriginPolicy materialize(String id, long version, String materializerVersion) {
    return OriginPolicy.materialize(
        id, version, 1, materializerVersion, Canonicalizers.byDimensions("id"), BUDGET);
  }

  private static OriginPolicy fallback() {
    return OriginPolicy.deny("fallback", 1, Integer.MIN_VALUE);
  }

  private static PolicyMatcher nullReturningMatcher() {
    return (PolicyMatcher)
        Proxy.newProxyInstance(
            PolicyMatcher.class.getClassLoader(),
            new Class<?>[] {PolicyMatcher.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("classify")) {
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
