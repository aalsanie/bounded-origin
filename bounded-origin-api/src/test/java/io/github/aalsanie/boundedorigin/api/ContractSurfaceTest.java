package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContractSurfaceTest {
  @Test
  void strategyAndTrustVocabularyIsStable() {
    assertEquals(
        java.util.List.of(
            ExecutionStrategy.ARTIFACT_ONLY,
            ExecutionStrategy.BOUNDED_COMPUTE,
            ExecutionStrategy.MATERIALIZE,
            ExecutionStrategy.CLIENT_COMPUTE,
            ExecutionStrategy.DENY),
        java.util.List.of(ExecutionStrategy.values()));
    assertEquals(
        java.util.List.of(TrustLevel.UNTRUSTED, TrustLevel.TRUSTED),
        java.util.List.of(TrustLevel.values()));
  }
}
