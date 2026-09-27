package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.DenialReason;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.proxy.HttpOperation;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BenchmarkFixturesTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 16, 128, 256})
  void startupFixturesObeyProductionLimitsAndEveryPhaseUsesThem(int count)
      throws java.io.IOException, ConfigurationException {
    String yaml = BenchmarkFixtures.yaml(count, BenchmarkFixtures.Shape.CAPTURE, 1, false, false);
    assertTrue(yaml.getBytes(StandardCharsets.UTF_8).length <= ConfigurationLimits.MAX_BYTES);
    var loaded = BenchmarkFixtures.load(yaml);
    assertEquals(count, loaded.routes().size());
    ConfiguredRuntime.validate(loaded);

    ConfigurationBenchmark benchmark = new ConfigurationBenchmark();
    benchmark.routeCount = count;
    benchmark.setup();
    try {
      assertEquals(loaded, benchmark.boundedYamlLoadAndDecode());
      assertEquals(loaded, benchmark.completeLoadAndCompile());
      assertEquals(loaded, benchmark.semanticValidationAndCompilation());
      assertEquals(loaded.gateway(), benchmark.gatewayFieldValidation());
      var table = assertInstanceOf(CompiledRouteTable.class, benchmark.routeTemplateCompilation());
      var match =
          table
              .match(
                  "GET",
                  BenchmarkFixtures.HOST,
                  "/route/0/item",
                  ConfigurationModel.Trust.UNTRUSTED)
              .orElseThrow();
      assertEquals("route-0", match.route().id());
      var engine =
          assertInstanceOf(
              io.github.aalsanie.boundedorigin.core.PolicyEngine.class,
              benchmark.policyCompilation());
      assertEquals(
          "route-0",
          selected(engine.evaluate(BenchmarkFixtures.request("/route/0/item", "q0=value")))
              .policy()
              .id());
    } finally {
      benchmark.cleanup();
    }
  }

  @Test
  void fixtureBoundsAreExplicit() {
    for (int count : List.of(0, 1025)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> BenchmarkFixtures.yaml(count, BenchmarkFixtures.Shape.FIXED, 1, true, false));
    }
    for (int dimensions : List.of(0, 129)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> BenchmarkFixtures.yaml(1, BenchmarkFixtures.Shape.FIXED, dimensions, true, false));
    }
  }

  @Test
  void everyRouteShapeAndPositionHasTheDeclaredDecision()
      throws java.io.IOException, ConfigurationException {
    for (int count : List.of(16, 128, 256)) {
      for (var shape : BenchmarkFixtures.Shape.values()) {
        for (var position : BenchmarkFixtures.Position.values()) {
          RouteEvaluationBenchmark benchmark = new RouteEvaluationBenchmark();
          benchmark.routeCount = count;
          benchmark.shape = shape.name();
          benchmark.position = position.name();
          benchmark.setup();
          var result = benchmark.evaluate();
          if (position == BenchmarkFixtures.Position.MISS) {
            assertEquals(
                DenialReason.NO_MATCH,
                assertInstanceOf(OriginDecision.Denied.class, result).reason());
          } else {
            int index =
                switch (position) {
                  case FIRST -> 0;
                  case MIDDLE -> count / 2;
                  case LAST -> count - 1;
                  case MISS -> throw new AssertionError("handled above");
                };
            var selected = selected(result);
            assertEquals("route-" + index, selected.policy().id());
            assertEquals(
                BenchmarkFixtures.path(count, shape, position) + "?q0=value",
                HttpOperation.from(selected.operation()).target());
          }
        }
      }
    }
  }

  @Test
  void overlappingFixtureResolvesPrecedenceWithoutLosingDenial()
      throws java.io.IOException, ConfigurationException {
    for (int count : List.of(16, 128, 256)) {
      var configuration = BenchmarkFixtures.load(BenchmarkFixtures.overlappingYaml(count));
      assertEquals(count, configuration.routes().size());
      var engine = BenchmarkFixtures.engine(configuration);
      assertEquals(
          "route-0",
          selected(engine.evaluate(BenchmarkFixtures.request("/route/0/item", "q0=value")))
              .policy()
              .id());
      assertEquals(
          DenialReason.POLICY_DENIED,
          assertInstanceOf(
                  OriginDecision.Denied.class,
                  engine.evaluate(BenchmarkFixtures.request("/route/unmatched", "")))
              .reason());
      OverlapEvaluationBenchmark benchmark = new OverlapEvaluationBenchmark();
      benchmark.routeCount = count;
      benchmark.setup();
      assertEquals("route-0", selected(benchmark.evaluateOverlappingPrecedence()).policy().id());
    }
  }

  @Test
  void semanticFixturesPreserveEquivalenceAndDistinctions()
      throws java.io.IOException, ConfigurationException {
    for (int dimensions : List.of(1, 8, 32, 128)) {
      for (boolean ordered : List.of(false, true)) {
        Map<BenchmarkFixtures.QueryCase, String> keys = new LinkedHashMap<>();
        for (var queryCase : BenchmarkFixtures.QueryCase.values()) {
          SemanticKeyBenchmark benchmark = new SemanticKeyBenchmark();
          benchmark.queryDimensions = dimensions;
          benchmark.ordered = ordered;
          benchmark.queryCase = queryCase.name();
          benchmark.setup();
          String key = benchmark.projectAndCanonicalize();
          assertEquals(key, benchmark.canonicalizeProjectedOperation());
          keys.put(queryCase, key);
        }
        String selected = keys.get(BenchmarkFixtures.QueryCase.SELECTED);
        assertEquals(selected, keys.get(BenchmarkFixtures.QueryCase.NOISE));
        assertEquals(selected, keys.get(BenchmarkFixtures.QueryCase.ENCODED));
        if (ordered && dimensions > 1) {
          assertNotEquals(selected, keys.get(BenchmarkFixtures.QueryCase.REVERSED));
        } else {
          assertEquals(selected, keys.get(BenchmarkFixtures.QueryCase.REVERSED));
        }
        assertEquals(
            5,
            Set.of(
                    selected,
                    keys.get(BenchmarkFixtures.QueryCase.DUPLICATES),
                    keys.get(BenchmarkFixtures.QueryCase.MISSING),
                    keys.get(BenchmarkFixtures.QueryCase.BARE),
                    keys.get(BenchmarkFixtures.QueryCase.EMPTY))
                .size());
      }
    }
  }

  @Test
  void fixedProgrammaticComparisonDoesEquivalentWorkForItsCohort()
      throws java.io.IOException, ConfigurationException {
    for (int count : List.of(1, 16, 128, 256)) {
      var configured =
          BenchmarkFixtures.engine(
              BenchmarkFixtures.load(
                  BenchmarkFixtures.yaml(count, BenchmarkFixtures.Shape.FIXED, 1, true, true)));
      var programmatic = ProgrammaticPolicies.fixedPaths(count);
      for (var position : BenchmarkFixtures.Position.values()) {
        String path = BenchmarkFixtures.path(count, BenchmarkFixtures.Shape.FIXED, position);
        for (String query :
            new String[] {null, "", "q0=value", "q0=&q0&noise=%76alue", "q0=second&q0=first"}) {
          RequestDescriptor request = BenchmarkFixtures.request(path, query);
          equivalent(configured.evaluate(request), programmatic.evaluate(request));
        }
        ProgrammaticComparisonBenchmark benchmark = new ProgrammaticComparisonBenchmark();
        benchmark.routeCount = count;
        benchmark.position = position.name();
        benchmark.setup();
        equivalent(benchmark.configured(), benchmark.programmatic());
      }
      RequestDescriptor base = BenchmarkFixtures.request("/route/0", "q0=value");
      for (String attribute : List.of("method", "host", "path")) {
        Map<String, List<String>> changed = new LinkedHashMap<>(base.attributes());
        changed.put(attribute, List.of("other"));
        var request = new RequestDescriptor(base.name(), changed, base.trustLevel());
        equivalent(configured.evaluate(request), programmatic.evaluate(request));
      }
      for (var request :
          List.of(
              new RequestDescriptor("other", base.attributes(), base.trustLevel()),
              new RequestDescriptor(base.name(), base.attributes(), TrustLevel.TRUSTED))) {
        equivalent(configured.evaluate(request), programmatic.evaluate(request));
      }
    }
  }

  private static OriginDecision.Selected selected(OriginDecision decision) {
    return assertInstanceOf(OriginDecision.Selected.class, decision);
  }

  private static void equivalent(OriginDecision configured, OriginDecision programmatic) {
    if (configured instanceof OriginDecision.Selected expected) {
      var actual = selected(programmatic);
      assertEquals(expected.operation(), actual.operation());
      assertEquals(expected.operationKey(), actual.operationKey());
      assertEquals(expected.policy().strategy(), actual.policy().strategy());
      assertEquals(expected.policy().precedence(), actual.policy().precedence());
    } else {
      assertEquals(configured, programmatic);
    }
  }
}
