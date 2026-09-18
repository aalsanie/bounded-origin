package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.DenialReason;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfiguredPolicyAdversarialTest {
  private static final String BODY_SHA256 = "a".repeat(64);

  @Test
  void querySelectorLimitIsEnforcedAtTheExactBoundary() throws ConfigurationException {
    ConfigurationTestSupport.ObjectFixture fixture = ConfigurationTestSupport.objectFixture();
    List<String> maximum = new ArrayList<>();
    for (int index = 0; index < ConfigurationLimits.MAX_DIMENSIONS; index++) {
      maximum.add("q" + index);
    }
    fixture.renderQuery().put("include", maximum);

    ConfigurationModel.RuntimeConfiguration accepted = ConfigurationDecoder.decode(fixture.root());

    assertEquals(
        ConfigurationLimits.MAX_DIMENSIONS,
        accepted
            .routes()
            .getFirst()
            .key()
            .orElseThrow()
            .query()
            .orElseThrow()
            .include()
            .size());

    maximum.add("overflow");
    ConfigurationException failure =
        assertThrows(ConfigurationException.class, () -> ConfigurationDecoder.decode(fixture.root()));

    assertTrue(\n        failure.getMessage().contains("maximum entries " + ConfigurationLimits.MAX_DIMENSIONS));
  }

  @Test
  void largeSelectedValuesAndUnselectedNoiseCannotExpandSemanticIdentity()
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route =
        ConfigurationDecoder.decode(ConfigurationTestSupport.objectFixture().root())
            .routes()
            .getFirst();
    SemanticKeyPlan plan =
        SemanticKeyPlan.compile(route, Set.of("id"), "configuration.routes[0]");

    String capture = "c".repeat(4_096);
    String selected = "v".repeat(4_096);
    StringBuilder noisyQuery = new StringBuilder("variant=").append(selected);
    for (int index = 0; index < 128; index++) {
      noisyQuery.append("&noise").append(index).append('=').append("x".repeat(32));
    }

    String baseline =
        identity(plan, requestWithQuery("variant=" + selected), Map.of("id", capture));
    String noisy = identity(plan, requestWithQuery(noisyQuery.toString()), Map.of("id", capture));
    String changed =
        identity(plan, requestWithQuery("variant=" + selected + "x"), Map.of("id", capture));

    assertEquals(baseline, noisy);
    assertNotEquals(baseline, changed);
  }

  @Test
  void extremePrecedenceOrderingIsIndependentOfInputOrder() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration highest =
        RouteTemplateTestSupport.route("highest", Integer.MAX_VALUE, "/render/{id}");
    ConfigurationModel.RouteConfiguration lowest =
        RouteTemplateTestSupport.route("lowest", Integer.MIN_VALUE + 1, "/render/fixed");

    for (List<ConfigurationModel.RouteConfiguration> routes :
        List.of(List.of(highest, lowest), List.of(lowest, highest))) {
      CompiledRouteTable table = RouteTemplateCompiler.compile(routes);

      assertEquals(
          "highest",
          table
              .match("GET", "example.com", "/render/fixed", ConfigurationModel.Trust.UNTRUSTED)
              .orElseThrow()
              .route()
              .id());
    }
  }

  @Test
  void hostileConfiguredClientParametersRemainDataAndFallbackStillDenies()
      throws ConfigurationException {
    ConfigurationTestSupport.ObjectFixture fixture = ConfigurationTestSupport.objectFixture();
    String hostileKey = "quote\"slash\\";
    String hostileValue = "\b\f\n\r\t\u0001\"\\value";
    fixture.clientParameters().clear();
    fixture.clientParameters().put(hostileKey, hostileValue);

    ConfigurationModel.RuntimeConfiguration configuration =
        ConfigurationDecoder.decode(fixture.root());
    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            configuration,
            new io.github.aalsanie.boundedorigin.api.Budget(
                8, 64, Duration.ofSeconds(20), 1_048_576));

    OriginDecision.Selected selected =
        assertInstanceOf(OriginDecision.Selected.class, engine.evaluate(requestAtPath("/client/id")));
    ClientComputation computation = selected.policy().clientComputation().orElseThrow();

    assertEquals(Map.of(hostileKey, hostileValue), computation.parameters());

    OriginDecision.Denied fallback =
        assertInstanceOf(OriginDecision.Denied.class, engine.evaluate(requestAtPath("/missing")));
    assertEquals(DenialReason.NO_MATCH, fallback.reason());
    assertEquals(List.of("default-deny"), fallback.policyIds());
  }

  private static String identity(
      SemanticKeyPlan plan, RequestDescriptor request, Map<String, String> captures) {
    return plan.canonicalizer().canonicalize(plan.operation(request, captures));
  }

  private static RequestDescriptor requestWithQuery(String query) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("host", List.of("example.com"));
    attributes.put("path", List.of("/render/id"));
    attributes.put("query", List.of(query));
    attributes.put("body-sha256", List.of(BODY_SHA256));
    return new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);
  }

  private static RequestDescriptor requestAtPath(String path) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("host", List.of("example.com"));
    attributes.put("path", List.of(path));
    attributes.put("body-sha256", List.of(BODY_SHA256));
    return new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);
  }
}
