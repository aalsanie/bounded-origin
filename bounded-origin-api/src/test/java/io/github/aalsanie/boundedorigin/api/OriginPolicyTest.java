package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginPolicyTest {
  private static final Canonicalizer CANONICALIZER = Canonicalizers.byDimensions("id");
  private static final Budget BUDGET = new Budget(2, 4, Duration.ofSeconds(1), 1024);

  @Test
  void factoriesExposeOnlyStrategySpecificState() {
    OriginPolicy artifact = OriginPolicy.artifactOnly("artifact", 1, 10, "m1", CANONICALIZER);
    OriginPolicy bounded =
        OriginPolicy.boundedCompute("bounded", 2, 20, "m2", CANONICALIZER, BUDGET);
    OriginPolicy materialize =
        OriginPolicy.materialize("materialize", 3, 30, "m3", CANONICALIZER, BUDGET);
    ClientComputation computation = new ClientComputation("client", "c1", Map.of("x", "y"));
    OriginPolicy client =
        OriginPolicy.clientCompute("client", 4, 40, "m4", CANONICALIZER, computation);
    OriginPolicy deny = OriginPolicy.deny("deny", 5, 50);

    assertEquals(ExecutionStrategy.ARTIFACT_ONLY, artifact.strategy());
    assertEquals(ExecutionStrategy.BOUNDED_COMPUTE, bounded.strategy());
    assertEquals(ExecutionStrategy.MATERIALIZE, materialize.strategy());
    assertEquals(ExecutionStrategy.CLIENT_COMPUTE, client.strategy());
    assertEquals(ExecutionStrategy.DENY, deny.strategy());
    assertEquals("bounded", bounded.id());
    assertEquals(2, bounded.version());
    assertEquals(20, bounded.precedence());
    assertEquals("m2", bounded.materializerVersion().orElseThrow());
    assertEquals(BUDGET, bounded.budget().orElseThrow());
    assertTrue(bounded.canonicalizer().isPresent());
    assertFalse(bounded.clientComputation().isPresent());
    assertEquals(computation, client.clientComputation().orElseThrow());
    assertFalse(client.budget().isPresent());
    assertFalse(deny.canonicalizer().isPresent());
    assertFalse(deny.materializerVersion().isPresent());
    assertFalse(deny.budget().isPresent());
    assertFalse(deny.clientComputation().isPresent());
  }

  @Test
  void rejectsInvalidFactoryArguments() {
    assertThrows(NullPointerException.class, () -> OriginPolicy.deny(null, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> OriginPolicy.deny(" ", 0, 0));
    assertThrows(IllegalArgumentException.class, () -> OriginPolicy.deny("x", -1, 0));
    assertThrows(NullPointerException.class, () -> OriginPolicy.artifactOnly("x", 0, 0, "m", null));
    assertThrows(
        NullPointerException.class,
        () -> OriginPolicy.artifactOnly("x", 0, 0, null, CANONICALIZER));
    assertThrows(
        IllegalArgumentException.class,
        () -> OriginPolicy.artifactOnly("x", 0, 0, " ", CANONICALIZER));
    assertThrows(
        NullPointerException.class,
        () -> OriginPolicy.boundedCompute("x", 0, 0, "m", CANONICALIZER, null));
    assertThrows(
        NullPointerException.class,
        () -> OriginPolicy.materialize("x", 0, 0, "m", CANONICALIZER, null));
    assertThrows(
        NullPointerException.class,
        () -> OriginPolicy.clientCompute("x", 0, 0, "m", CANONICALIZER, null));
  }
}
