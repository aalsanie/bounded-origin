package io.github.aalsanie.boundedorigin.core;

import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.DenialReason;
import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PolicyEngine {
  private final List<PolicyRule> rules;
  private final OriginPolicy fallbackPolicy;

  public PolicyEngine(List<PolicyRule> rules, OriginPolicy fallbackPolicy) {
    Objects.requireNonNull(rules, "rules");
    this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
    if (fallbackPolicy.strategy() != ExecutionStrategy.DENY) {
      throw new IllegalArgumentException("fallbackPolicy must use DENY strategy");
    }

    List<PolicyRule> copy = List.copyOf(rules);
    Set<String> policyIds = new HashSet<>();
    policyIds.add(fallbackPolicy.id());
    for (PolicyRule rule : copy) {
      Objects.requireNonNull(rule, "rule");
      if (!policyIds.add(rule.policy().id())) {
        throw new IllegalArgumentException("duplicate policy id " + rule.policy().id());
      }
    }
    this.rules = copy;
  }

  public OriginDecision evaluate(RequestDescriptor request) {
    Objects.requireNonNull(request, "request");
    int bestPrecedence = Integer.MIN_VALUE;
    List<PolicyRule> bestRules = new ArrayList<>();
    List<Operation> bestOperations = new ArrayList<>();

    for (PolicyRule rule : rules) {
      Optional<Operation> result;
      try {
        result = Objects.requireNonNull(rule.matcher().classify(request), "matcher result");
      } catch (RuntimeException exception) {
        return denied(DenialReason.POLICY_ERROR, List.of(rule.policy().id()));
      }

      if (result.isEmpty()) {
        continue;
      }

      int precedence = rule.policy().precedence();
      if (precedence > bestPrecedence) {
        bestPrecedence = precedence;
        bestRules.clear();
        bestOperations.clear();
      }
      if (precedence == bestPrecedence) {
        bestRules.add(rule);
        bestOperations.add(result.get());
      }
    }

    if (bestRules.isEmpty()) {
      return denied(DenialReason.NO_MATCH, List.of(fallbackPolicy.id()));
    }

    if (bestRules.size() > 1) {
      List<String> policyIds = bestRules.stream().map(rule -> rule.policy().id()).sorted().toList();
      return denied(DenialReason.AMBIGUOUS_POLICY, policyIds);
    }

    OriginPolicy policy = bestRules.getFirst().policy();
    Operation operation = bestOperations.getFirst();
    if (policy.strategy() == ExecutionStrategy.DENY) {
      return denied(DenialReason.POLICY_DENIED, List.of(policy.id()));
    }

    try {
      Canonicalizer canonicalizer = policy.canonicalizer().orElseThrow();
      String materializerVersion = policy.materializerVersion().orElseThrow();
      String semanticIdentity = canonicalizer.canonicalize(operation);
      OperationKey key =
          new OperationKey(policy.id(), policy.version(), semanticIdentity, materializerVersion);
      return new OriginDecision.Selected(policy, operation, key);
    } catch (RuntimeException exception) {
      return denied(DenialReason.POLICY_ERROR, List.of(policy.id()));
    }
  }

  private static OriginDecision.Denied denied(DenialReason reason, List<String> policyIds) {
    return new OriginDecision.Denied(reason, policyIds);
  }
}
