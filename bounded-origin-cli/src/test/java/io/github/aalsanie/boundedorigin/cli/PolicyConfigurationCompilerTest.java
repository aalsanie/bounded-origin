package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.DenialReason;
import io.github.aalsanie.boundedorigin.api.ExecutionStrategy;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyConfigurationCompilerTest {
  private static final Budget GLOBAL_BUDGET =
      new Budget(8, 64, Duration.ofSeconds(20), 1_048_576);
  private static final String BODY_SHA = "a".repeat(64);

  @Test
  void compilesEveryExecutionStrategy() throws ConfigurationException {
    ConfigurationModel.RuntimeConfiguration base = baseConfiguration();
    List<ConfigurationModel.RouteConfiguration> routes =
        List.of(
            keyedRoute("artifact", "/artifact/{id}", 500, ConfigurationModel.Strategy.ARTIFACT_ONLY),
            boundedRoute(
                "bounded", "/bounded/{id}", 400, ConfigurationModel.Strategy.BOUNDED_COMPUTE),
            boundedRoute(
                "materialize", "/materialize/{id}", 300, ConfigurationModel.Strategy.MATERIALIZE),
            clientRoute("client-compute", "/client-compute/{id}", 200),
            denyRoute("blocked", "/blocked/{id}", 100));
    PolicyEngine engine = PolicyConfigurationCompiler.compile(withRoutes(base, routes), GLOBAL_BUDGET);

    assertSelected(engine, "/artifact/a", ExecutionStrategy.ARTIFACT_ONLY);
    assertSelected(engine, "/bounded/a", ExecutionStrategy.BOUNDED_COMPUTE);
    OriginDecision.Selected materialized =
        assertSelected(engine, "/materialize/a", ExecutionStrategy.MATERIALIZE);
    assertEquals(2, materialized.policy().budget().orElseThrow().maxActive());

    OriginDecision.Selected client =
        assertSelected(engine, "/client-compute/a", ExecutionStrategy.CLIENT_COMPUTE);
    ClientComputation computation = client.policy().clientComputation().orElseThrow();
    assertEquals("wasm", computation.type());
    assertEquals("v1", computation.version());
    assertEquals("strict", computation.parameters().get("mode"));

    OriginDecision.Denied blocked =
        assertInstanceOf(OriginDecision.Denied.class, engine.evaluate(request("/blocked/a")));
    assertEquals(DenialReason.POLICY_DENIED, blocked.reason());
    assertEquals(List.of("blocked"), blocked.policyIds());

    OriginDecision.Denied fallback =
        assertInstanceOf(OriginDecision.Denied.class, engine.evaluate(request("/missing/a")));
    assertEquals(DenialReason.NO_MATCH, fallback.reason());
    assertEquals(List.of("default-deny"), fallback.policyIds());
  }

  @Test
  void trustComesOnlyFromRequestDescriptorTrustLevel() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route =
        keyedRoute("artifact", "/artifact/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET);

    OriginDecision.Selected untrusted =
        assertInstanceOf(
            OriginDecision.Selected.class,
            engine.evaluate(request("/artifact/a", TrustLevel.UNTRUSTED, true)));
    OriginDecision.Denied trusted =
        assertInstanceOf(
            OriginDecision.Denied.class,
            engine.evaluate(request("/artifact/a", TrustLevel.TRUSTED, true)));

    assertEquals("artifact", untrusted.policy().id());
    assertEquals(DenialReason.NO_MATCH, trusted.reason());
  }

  @Test
  void strategySpecificFieldsFailClosedAtStartup() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration artifact =
        keyedRoute("artifact", "/artifact/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    ConfigurationModel.RouteConfiguration bounded =
        boundedRoute("bounded", "/bounded/{id}", 100, ConfigurationModel.Strategy.BOUNDED_COMPUTE);
    ConfigurationModel.RouteConfiguration materialize =
        boundedRoute("materialize", "/materialize/{id}", 100, ConfigurationModel.Strategy.MATERIALIZE);
    ConfigurationModel.RouteConfiguration client = clientRoute("client", "/client/{id}", 100);
    ConfigurationModel.RouteConfiguration deny = denyRoute("deny", "/deny/{id}", 100);

    assertCompileFailure(
        copy(
            artifact,
            artifact.key(),
            artifact.materializerVersion(),
            Optional.of(policyBudget()),
            Optional.empty()),
        "budget is not allowed for ARTIFACT_ONLY");
    assertCompileFailure(
        copy(
            artifact,
            artifact.key(),
            artifact.materializerVersion(),
            Optional.empty(),
            Optional.of(clientComputation())),
        "client-computation is not allowed for ARTIFACT_ONLY");
    assertCompileFailure(
        copy(bounded, bounded.key(), bounded.materializerVersion(), Optional.empty(), Optional.empty()),
        "budget is required for BOUNDED_COMPUTE");
    assertCompileFailure(
        copy(
            bounded,
            bounded.key(),
            bounded.materializerVersion(),
            bounded.budget(),
            Optional.of(clientComputation())),
        "client-computation is not allowed for BOUNDED_COMPUTE");
    assertCompileFailure(
        copy(
            materialize,
            materialize.key(),
            materialize.materializerVersion(),
            Optional.empty(),
            Optional.empty()),
        "budget is required for MATERIALIZE");
    assertCompileFailure(
        copy(client, client.key(), client.materializerVersion(), Optional.empty(), Optional.empty()),
        "client-computation is required for CLIENT_COMPUTE");
    assertCompileFailure(
        copy(
            client,
            client.key(),
            client.materializerVersion(),
            Optional.of(policyBudget()),
            client.clientComputation()),
        "budget is not allowed for CLIENT_COMPUTE");
    assertCompileFailure(
        copy(deny, keyedIdentity(), Optional.empty(), Optional.empty(), Optional.empty()),
        "key is not allowed for DENY");
    assertCompileFailure(
        copy(deny, Optional.empty(), Optional.of("v1"), Optional.empty(), Optional.empty()),
        "materializer-version is not allowed for DENY");
    assertCompileFailure(
        copy(deny, Optional.empty(), Optional.empty(), Optional.of(policyBudget()), Optional.empty()),
        "budget is not allowed for DENY");
    assertCompileFailure(
        copy(
            deny,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(clientComputation())),
        "client-computation is not allowed for DENY");
    assertCompileFailure(
        copy(artifact, Optional.empty(), artifact.materializerVersion(), Optional.empty(), Optional.empty()),
        "key is required for ARTIFACT_ONLY");
    assertCompileFailure(
        copy(artifact, artifact.key(), Optional.empty(), Optional.empty(), Optional.empty()),
        "materializer-version is required for ARTIFACT_ONLY");
    assertCompileFailure(
        copy(artifact, artifact.key(), Optional.of(" "), Optional.empty(), Optional.empty()),
        "materializer-version must not be blank");
  }

  @Test
  void rejectsEveryPolicyBudgetThatExceedsGlobalLimit() throws ConfigurationException {
    assertBudgetFailure(
        new ConfigurationModel.BudgetConfiguration(9, 1, Duration.ofSeconds(1), 1),
        "max-active");
    assertBudgetFailure(
        new ConfigurationModel.BudgetConfiguration(1, 65, Duration.ofSeconds(1), 1),
        "max-queued");
    assertBudgetFailure(
        new ConfigurationModel.BudgetConfiguration(1, 1, Duration.ofSeconds(21), 1),
        "max-execution-duration");
    assertBudgetFailure(
        new ConfigurationModel.BudgetConfiguration(1, 1, Duration.ofSeconds(1), 1_048_577),
        "max-result-bytes");
  }

  @Test
  void acceptsPolicyBudgetExactlyAtGlobalLimits() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration baseRoute =
        boundedRoute("bounded", "/bounded/{id}", 100, ConfigurationModel.Strategy.BOUNDED_COMPUTE);
    ConfigurationModel.BudgetConfiguration exact =
        new ConfigurationModel.BudgetConfiguration(
            GLOBAL_BUDGET.maxActive(),
            GLOBAL_BUDGET.maxQueued(),
            GLOBAL_BUDGET.timeout(),
            GLOBAL_BUDGET.maxResultBytes());
    ConfigurationModel.RouteConfiguration route =
        copy(
            baseRoute,
            baseRoute.key(),
            baseRoute.materializerVersion(),
            Optional.of(exact),
            Optional.empty());

    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET);
    OriginDecision.Selected selected =
        assertInstanceOf(OriginDecision.Selected.class, engine.evaluate(request("/bounded/a")));

    assertEquals(GLOBAL_BUDGET, selected.policy().budget().orElseThrow());
  }

  @Test
  void invalidTypedBudgetFailsAsConfigurationError() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration baseRoute =
        boundedRoute("bounded", "/bounded/{id}", 100, ConfigurationModel.Strategy.BOUNDED_COMPUTE);
    ConfigurationModel.BudgetConfiguration invalid =
        new ConfigurationModel.BudgetConfiguration(0, 1, Duration.ofSeconds(1), 1);
    ConfigurationModel.RouteConfiguration route =
        copy(
            baseRoute,
            baseRoute.key(),
            baseRoute.materializerVersion(),
            Optional.of(invalid),
            Optional.empty());

    assertCompileFailure(route, "budget is invalid");
  }

  @Test
  void rejectsUnsafeFallbacksAndImpossiblePrecedence() throws ConfigurationException {
    ConfigurationModel.RuntimeConfiguration base = baseConfiguration();
    ConfigurationModel.RouteConfiguration route =
        keyedRoute("artifact", "/artifact/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);

    assertConfigurationFailure(
        new ConfigurationModel.RuntimeConfiguration(
            base.schema(), base.gateway(), base.store(), List.of(route), null),
        "fallback must not be null");
    assertConfigurationFailure(
        withFallback(
            withRoutes(base, List.of(route)),
            new ConfigurationModel.FallbackConfiguration(
                " ", 1, Integer.MIN_VALUE, ConfigurationModel.Strategy.DENY)),
        "fallback.id must not be blank");
    assertConfigurationFailure(
        withFallback(
            withRoutes(base, List.of(route)),
            new ConfigurationModel.FallbackConfiguration(
                "default-deny", -1, Integer.MIN_VALUE, ConfigurationModel.Strategy.DENY)),
        "fallback.version must be non-negative");
    assertConfigurationFailure(
        withFallback(
            withRoutes(base, List.of(route)),
            new ConfigurationModel.FallbackConfiguration(
                "default-deny", 1, Integer.MIN_VALUE, ConfigurationModel.Strategy.MATERIALIZE)),
        "strategy must be DENY");
    assertConfigurationFailure(
        withFallback(
            withRoutes(base, List.of(route)),
            new ConfigurationModel.FallbackConfiguration(
                "default-deny", 1, 100, ConfigurationModel.Strategy.DENY)),
        "precedence must exceed configuration.fallback.precedence");
    assertConfigurationFailure(
        withFallback(
            withRoutes(base, List.of(route)),
            new ConfigurationModel.FallbackConfiguration(
                "artifact", 1, Integer.MIN_VALUE, ConfigurationModel.Strategy.DENY)),
        "duplicate policy id artifact");
  }

  @Test
  void rejectsMalformedRouteShapes() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base =
        keyedRoute("artifact", "/artifact/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);

    assertCompileFailure(withIdentity(base, null, 1, base.strategy()), ".id must not be blank");
    assertCompileFailure(withIdentity(base, " ", 1, base.strategy()), ".id must not be blank");
    assertCompileFailure(withIdentity(base, "artifact", -1, base.strategy()), ".version must be non-negative");
    assertCompileFailure(withIdentity(base, "artifact", 1, null), ".strategy must not be null");
    assertCompileFailure(
        new ConfigurationModel.RouteConfiguration(
            base.id(),
            base.version(),
            base.precedence(),
            base.match(),
            base.strategy(),
            null,
            base.materializerVersion(),
            base.budget(),
            base.clientComputation()),
        "optional policy fields must not be null");
  }

  @Test
  void rejectsNullCompilerInputs() throws ConfigurationException {
    assertConfigurationFailure(null, "configuration must not be null");
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () -> PolicyConfigurationCompiler.compile(baseConfiguration(), null));
    assertTrue(failure.getMessage().contains("global budget must not be null"));
  }

  @Test
  void rejectsAmbiguousSamePrecedenceRoutesBeforeEngineConstruction()
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration first =
        keyedRoute("first", "/same/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    ConfigurationModel.RouteConfiguration second =
        keyedRoute("second", "/same/{value}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);

    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () ->
                PolicyConfigurationCompiler.compile(
                    withRoutes(baseConfiguration(), List.of(first, second)), GLOBAL_BUDGET));

    assertTrue(failure.getMessage().contains("ambiguous same-precedence overlap"));
  }

  @Test
  void malformedMatchedRequestFailsClosedToPolicyError() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration lower =
        keyedRoute("lower", "/lower/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    ConfigurationModel.RouteConfiguration higher =
        keyedRoute("higher", "/higher/{id}", 200, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            withRoutes(baseConfiguration(), List.of(lower, higher)), GLOBAL_BUDGET);
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("path", List.of("/higher/a"));
    attributes.put("body-sha256", List.of(BODY_SHA));
    RequestDescriptor malformed =
        new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);

    OriginDecision.Denied denied =
        assertInstanceOf(OriginDecision.Denied.class, engine.evaluate(malformed));

    assertEquals(DenialReason.POLICY_ERROR, denied.reason());
    assertEquals(List.of("higher"), denied.policyIds());
  }

  @Test
  void nonHttpRequestsCannotReachConfiguredRoutes() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route =
        keyedRoute("artifact", "/artifact/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET);
    RequestDescriptor request =
        new RequestDescriptor("other.request", Map.of("x", List.of("y")), TrustLevel.UNTRUSTED);

    OriginDecision.Denied denied =
        assertInstanceOf(OriginDecision.Denied.class, engine.evaluate(request));

    assertEquals(DenialReason.NO_MATCH, denied.reason());
    assertEquals(List.of("default-deny"), denied.policyIds());
  }

  @Test
  void denyRoutesDoNotRequireSemanticBodyMaterial() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route = denyRoute("deny", "/deny/{id}", 100);
    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET);
    RequestDescriptor request =
        new RequestDescriptor(
            "http.request",
            Map.of(
                "method", List.of("GET"),
                "host", List.of("example.com"),
                "path", List.of("/deny/a")),
            TrustLevel.UNTRUSTED);

    OriginDecision.Denied denied =
        assertInstanceOf(OriginDecision.Denied.class, engine.evaluate(request));

    assertEquals(DenialReason.POLICY_DENIED, denied.reason());
    assertEquals(List.of("deny"), denied.policyIds());
  }

  @Test
  void invalidSemanticBodyMaterialFailsClosed() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route =
        keyedRoute("artifact", "/artifact/{id}", 100, ConfigurationModel.Strategy.ARTIFACT_ONLY);
    PolicyEngine engine =
        PolicyConfigurationCompiler.compile(
            withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET);

    OriginDecision.Denied denied =
        assertInstanceOf(
            OriginDecision.Denied.class,
            engine.evaluate(request("/artifact/a", TrustLevel.UNTRUSTED, false, "bad")));

    assertEquals(DenialReason.POLICY_ERROR, denied.reason());
    assertEquals(List.of("artifact"), denied.policyIds());
  }

  private static OriginDecision.Selected assertSelected(
      PolicyEngine engine, String path, ExecutionStrategy strategy) {
    OriginDecision.Selected selected =
        assertInstanceOf(OriginDecision.Selected.class, engine.evaluate(request(path)));
    assertEquals(strategy, selected.policy().strategy());
    return selected;
  }

  private static void assertBudgetFailure(
      ConfigurationModel.BudgetConfiguration budget, String field) throws ConfigurationException {
    ConfigurationModel.RouteConfiguration baseRoute =
        boundedRoute("bounded", "/bounded/{id}", 100, ConfigurationModel.Strategy.BOUNDED_COMPUTE);
    ConfigurationModel.RouteConfiguration route =
        copy(
            baseRoute,
            baseRoute.key(),
            baseRoute.materializerVersion(),
            Optional.of(budget),
            Optional.empty());
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () ->
                PolicyConfigurationCompiler.compile(
                    withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET));
    assertTrue(failure.getMessage().contains(field + " exceeds global origin budget"));
  }

  private static void assertCompileFailure(
      ConfigurationModel.RouteConfiguration route, String expected) throws ConfigurationException {
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () ->
                PolicyConfigurationCompiler.compile(
                    withRoutes(baseConfiguration(), List.of(route)), GLOBAL_BUDGET));
    assertTrue(
        failure.getMessage().contains(expected),
        () -> "expected <" + expected + "> in <" + failure.getMessage() + ">");
  }

  private static void assertConfigurationFailure(
      ConfigurationModel.RuntimeConfiguration configuration, String expected) {
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () -> PolicyConfigurationCompiler.compile(configuration, GLOBAL_BUDGET));
    assertTrue(failure.getMessage().contains(expected));
  }

  private static ConfigurationModel.RuntimeConfiguration baseConfiguration()
      throws ConfigurationException {
    return ConfigurationDecoder.decode(ConfigurationTestSupport.objectFixture().root());
  }

  private static ConfigurationModel.RuntimeConfiguration withRoutes(
      ConfigurationModel.RuntimeConfiguration configuration,
      List<ConfigurationModel.RouteConfiguration> routes) {
    return new ConfigurationModel.RuntimeConfiguration(
        configuration.schema(),
        configuration.gateway(),
        configuration.store(),
        routes,
        configuration.fallback());
  }

  private static ConfigurationModel.RuntimeConfiguration withFallback(
      ConfigurationModel.RuntimeConfiguration configuration,
      ConfigurationModel.FallbackConfiguration fallback) {
    return new ConfigurationModel.RuntimeConfiguration(
        configuration.schema(),
        configuration.gateway(),
        configuration.store(),
        configuration.routes(),
        fallback);
  }

  private static ConfigurationModel.RouteConfiguration keyedRoute(
      String id, String path, int precedence, ConfigurationModel.Strategy strategy) {
    return new ConfigurationModel.RouteConfiguration(
        id,
        1,
        precedence,
        match(path),
        strategy,
        keyedIdentity(),
        Optional.of("v1"),
        Optional.empty(),
        Optional.empty());
  }

  private static ConfigurationModel.RouteConfiguration boundedRoute(
      String id, String path, int precedence, ConfigurationModel.Strategy strategy) {
    ConfigurationModel.RouteConfiguration route = keyedRoute(id, path, precedence, strategy);
    return copy(
        route,
        route.key(),
        route.materializerVersion(),
        Optional.of(policyBudget()),
        Optional.empty());
  }

  private static ConfigurationModel.RouteConfiguration clientRoute(
      String id, String path, int precedence) {
    ConfigurationModel.RouteConfiguration route =
        keyedRoute(id, path, precedence, ConfigurationModel.Strategy.CLIENT_COMPUTE);
    return copy(
        route,
        route.key(),
        route.materializerVersion(),
        Optional.empty(),
        Optional.of(clientComputation()));
  }

  private static ConfigurationModel.RouteConfiguration denyRoute(
      String id, String path, int precedence) {
    return new ConfigurationModel.RouteConfiguration(
        id,
        1,
        precedence,
        match(path),
        ConfigurationModel.Strategy.DENY,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private static ConfigurationModel.RouteConfiguration copy(
      ConfigurationModel.RouteConfiguration route,
      Optional<ConfigurationModel.KeyConfiguration> key,
      Optional<String> materializerVersion,
      Optional<ConfigurationModel.BudgetConfiguration> budget,
      Optional<ConfigurationModel.ClientComputationConfiguration> clientComputation) {
    return new ConfigurationModel.RouteConfiguration(
        route.id(),
        route.version(),
        route.precedence(),
        route.match(),
        route.strategy(),
        key,
        materializerVersion,
        budget,
        clientComputation);
  }

  private static ConfigurationModel.RouteConfiguration withIdentity(
      ConfigurationModel.RouteConfiguration route,
      String id,
      long version,
      ConfigurationModel.Strategy strategy) {
    return new ConfigurationModel.RouteConfiguration(
        id,
        version,
        route.precedence(),
        route.match(),
        strategy,
        route.key(),
        route.materializerVersion(),
        route.budget(),
        route.clientComputation());
  }

  private static ConfigurationModel.MatchConfiguration match(String path) {
    return new ConfigurationModel.MatchConfiguration(
        Optional.of("GET"),
        Optional.of("example.com"),
        path,
        Optional.of(ConfigurationModel.Trust.UNTRUSTED));
  }

  private static Optional<ConfigurationModel.KeyConfiguration> keyedIdentity() {
    return Optional.of(
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of("variant"), true))));
  }

  private static ConfigurationModel.BudgetConfiguration policyBudget() {
    return new ConfigurationModel.BudgetConfiguration(2, 4, Duration.ofSeconds(5), 4096);
  }

  private static ConfigurationModel.ClientComputationConfiguration clientComputation() {
    return new ConfigurationModel.ClientComputationConfiguration(
        "wasm", "v1", Map.of("mode", "strict"));
  }

  private static RequestDescriptor request(String path) {
    return request(path, TrustLevel.UNTRUSTED, false);
  }

  private static RequestDescriptor request(String path, TrustLevel trust, boolean spoofTrust) {
    return request(path, trust, spoofTrust, BODY_SHA);
  }

  private static RequestDescriptor request(
      String path, TrustLevel trust, boolean spoofTrust, String bodySha) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("host", List.of("example.com"));
    attributes.put("path", List.of(path));
    attributes.put("query", List.of("variant=one&ignored=two"));
    attributes.put("body-sha256", List.of(bodySha));
    if (spoofTrust) {
      attributes.put("trust", List.of("UNTRUSTED"));
    }
    return new RequestDescriptor("http.request", attributes, trust);
  }
}
