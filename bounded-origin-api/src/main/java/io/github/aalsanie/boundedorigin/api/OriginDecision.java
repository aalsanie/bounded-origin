package io.github.aalsanie.boundedorigin.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface OriginDecision permits OriginDecision.Selected, OriginDecision.Denied {
  record Selected(OriginPolicy policy, Operation operation, OperationKey operationKey)
      implements OriginDecision {
    public Selected {
      policy = Objects.requireNonNull(policy, "policy");
      operation = Objects.requireNonNull(operation, "operation");
      operationKey = Objects.requireNonNull(operationKey, "operationKey");
      if (policy.strategy() == ExecutionStrategy.DENY) {
        throw new IllegalArgumentException("selected decision cannot use DENY strategy");
      }
      if (!policy.id().equals(operationKey.policyId())) {
        throw new IllegalArgumentException("operationKey policyId must match policy");
      }
      if (policy.version() != operationKey.policyVersion()) {
        throw new IllegalArgumentException("operationKey policyVersion must match policy");
      }
      String materializerVersion = policy.materializerVersion().orElseThrow();
      if (!materializerVersion.equals(operationKey.materializerVersion())) {
        throw new IllegalArgumentException("operationKey materializerVersion must match policy");
      }
    }
  }

  record Denied(DenialReason reason, List<String> policyIds) implements OriginDecision {
    public Denied {
      reason = Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(policyIds, "policyIds");
      if (policyIds.isEmpty()) {
        throw new IllegalArgumentException("policyIds must not be empty");
      }
      Set<String> seen = new HashSet<>();
      for (String policyId : policyIds) {
        Objects.requireNonNull(policyId, "policyId");
        if (policyId.isBlank()) {
          throw new IllegalArgumentException("policyId must not be blank");
        }
        if (!seen.add(policyId)) {
          throw new IllegalArgumentException("policyIds must be unique");
        }
      }
      policyIds = List.copyOf(policyIds);
    }
  }
}
