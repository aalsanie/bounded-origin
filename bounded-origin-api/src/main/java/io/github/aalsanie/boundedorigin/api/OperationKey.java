package io.github.aalsanie.boundedorigin.api;

import java.util.Objects;

public record OperationKey(
    String policyId, long policyVersion, String semanticIdentity, String materializerVersion) {
  public OperationKey {
    policyId = requireNonBlank(policyId, "policyId");
    if (policyVersion < 0) {
      throw new IllegalArgumentException("policyVersion must be non-negative");
    }
    semanticIdentity = requireNonBlank(semanticIdentity, "semanticIdentity");
    materializerVersion = requireNonBlank(materializerVersion, "materializerVersion");
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
