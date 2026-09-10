package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.PolicyMatcher;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyRuleTest {
  @Test
  void validatesAndExposesComponents() {
    OriginPolicy policy = OriginPolicy.deny("deny", 0, 1);
    PolicyMatcher matcher = request -> Optional.empty();
    PolicyRule rule = new PolicyRule(policy, matcher);

    assertEquals(policy, rule.policy());
    assertEquals(matcher, rule.matcher());
    PolicyRule same = new PolicyRule(policy, matcher);
    assertEquals(rule, same);
    assertEquals(rule.hashCode(), same.hashCode());
    assertThrows(NullPointerException.class, () -> new PolicyRule(null, matcher));
    assertThrows(NullPointerException.class, () -> new PolicyRule(policy, null));
  }
}
