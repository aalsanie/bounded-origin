package io.github.aalsanie.boundedorigin.core;

import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.PolicyMatcher;
import java.util.Objects;

public record PolicyRule(OriginPolicy policy, PolicyMatcher matcher) {
  public PolicyRule {
    policy = Objects.requireNonNull(policy, "policy");
    matcher = Objects.requireNonNull(matcher, "matcher");
  }
}
