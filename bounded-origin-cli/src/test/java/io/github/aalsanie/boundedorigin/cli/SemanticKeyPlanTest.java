package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticKeyPlanTest {
  private static final String BODY_A = "a".repeat(64);
  private static final String BODY_B = "b".repeat(64);

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void canonicalizesSelectedQueryPairsAndPreservesMultiplicity(boolean clientCompute)
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route = renderRoute(clientCompute);
    SemanticKeyPlan plan = compile(route);

    String first =
        identity(
            plan,
            request(
                "GET", "example.com", "/render/%41", "ignored=x&variant=%7e&variant=a+b&variant"),
            Map.of("id", "%41"));
    String reordered =
        identity(
            plan,
            request("GET", "example.com", "/render/A", "variant&variant=a+b&variant=~&ignored=y"),
            Map.of("id", "A"));
    String fewer =
        identity(
            plan,
            request("GET", "example.com", "/render/A", "variant=a+b&variant=~"),
            Map.of("id", "A"));

    assertEquals(first, reordered);
    assertNotEquals(first, fewer);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void distinguishesMissingAndEmptySelectedQueryValues(boolean clientCompute)
      throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute(clientCompute));

    String missingEquals =
        identity(plan, request("GET", "example.com", "/render/a", "variant"), Map.of("id", "a"));
    String emptyValue =
        identity(plan, request("GET", "example.com", "/render/a", "variant="), Map.of("id", "a"));

    assertNotEquals(missingEquals, emptyValue);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void treatsPlusAsLiteralAndNormalizesOnlyUnreservedPercentEncodings(boolean clientCompute)
      throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute(clientCompute));

    String plus =
        identity(
            plan, request("GET", "example.com", "/render/a", "variant=a+b"), Map.of("id", "a"));
    String encodedSpace =
        identity(
            plan, request("GET", "example.com", "/render/a", "variant=a%20b"), Map.of("id", "a"));
    String encodedSlashLower =
        identity(
            plan, request("GET", "example.com", "/render/a", "variant=a%2fb"), Map.of("id", "a"));
    String encodedSlashUpper =
        identity(
            plan, request("GET", "example.com", "/render/a", "variant=a%2Fb"), Map.of("id", "a"));

    assertNotEquals(plus, encodedSpace);
    assertEquals(encodedSlashLower, encodedSlashUpper);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void preservesRawQueryWhenSelectedModeIsAbsent(boolean clientCompute)
      throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    useClientComputation(fixture, clientCompute);
    fixture.renderKey().remove("query");
    ConfigurationModel.RouteConfiguration route =
        ConfigurationDecoder.decode(fixture.root()).routes().getFirst();
    SemanticKeyPlan plan = compile(route);

    String first =
        identity(plan, request("GET", "example.com", "/render/a", "b=2&a=%7e"), Map.of("id", "a"));
    String reordered =
        identity(plan, request("GET", "example.com", "/render/a", "a=%7e&b=2"), Map.of("id", "a"));
    String normalizedAlias =
        identity(plan, request("GET", "example.com", "/render/a", "b=2&a=~"), Map.of("id", "a"));

    assertNotEquals(first, reordered);
    assertNotEquals(first, normalizedAlias);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void explicitEmptySelectedQueryIgnoresQuery(boolean clientCompute) throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of(), false)),
            List.of());
    ConfigurationModel.RouteConfiguration route = withKey(base, Optional.of(key));
    SemanticKeyPlan plan = compile(route);

    String first =
        identity(plan, request("GET", "example.com", "/render/a", "x=1"), Map.of("id", "a"));
    String second =
        identity(plan, request("GET", "example.com", "/render/a", "x=2"), Map.of("id", "a"));

    assertEquals(first, second);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void orderedSelectedQueryPreservesPairOrder(boolean clientCompute) throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(
                new ConfigurationModel.QueryKeyConfiguration(List.of("variant", "format"), false)),
            List.of());
    SemanticKeyPlan plan = compile(withKey(base, Optional.of(key)));

    String first =
        identity(
            plan,
            request("GET", "example.com", "/render/a", "variant=x&format=y"),
            Map.of("id", "a"));
    String second =
        identity(
            plan,
            request("GET", "example.com", "/render/a", "format=y&variant=x"),
            Map.of("id", "a"));

    assertNotEquals(first, second);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void wildcardRouteConstraintsParticipateInIdentity(boolean clientCompute)
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.MatchConfiguration wildcard =
        new ConfigurationModel.MatchConfiguration(
            Optional.empty(), Optional.empty(), "/render/{id}", Optional.empty());
    ConfigurationModel.RouteConfiguration route = withMatch(base, wildcard);
    SemanticKeyPlan plan = compile(route);
    Map<String, String> captures = Map.of("id", "a");

    String baseline =
        identity(plan, request("GET", "example.com", "/render/a", "variant=x"), captures);
    String method =
        identity(plan, request("POST", "example.com", "/render/a", "variant=x"), captures);
    String host =
        identity(plan, request("GET", "other.example", "/render/a", "variant=x"), captures);
    String trust =
        identity(
            plan,
            request("GET", "example.com", "/render/a", "variant=x", BODY_A, TrustLevel.TRUSTED),
            captures);

    assertNotEquals(baseline, method);
    assertNotEquals(baseline, host);
    assertNotEquals(baseline, trust);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void catchAllRouteIncludesFullPath(boolean clientCompute) throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.MatchConfiguration catchAll =
        new ConfigurationModel.MatchConfiguration(
            Optional.of("GET"), Optional.of("example.com"), "/render/**", Optional.empty());
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(List.of(), Optional.empty(), List.of());
    SemanticKeyPlan plan = compile(withKey(withMatch(base, catchAll), Optional.of(key)));

    String first = identity(plan, request("GET", "example.com", "/render/a", null), Map.of());
    String second = identity(plan, request("GET", "example.com", "/render/b", null), Map.of());

    assertNotEquals(first, second);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bodyDigestAlwaysParticipatesAndIsCaseNormalized(boolean clientCompute)
      throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute(clientCompute));
    Map<String, String> captures = Map.of("id", "a");

    String lower =
        identity(
            plan,
            request("GET", "example.com", "/render/a", "variant=x", BODY_A, TrustLevel.UNTRUSTED),
            captures);
    String upper =
        identity(
            plan,
            request(
                "GET",
                "example.com",
                "/render/a",
                "variant=x",
                BODY_A.toUpperCase(java.util.Locale.ROOT),
                TrustLevel.UNTRUSTED),
            captures);
    String other =
        identity(
            plan,
            request("GET", "example.com", "/render/a", "variant=x", BODY_B, TrustLevel.UNTRUSTED),
            captures);

    assertEquals(lower, upper);
    assertNotEquals(lower, other);
    assertNotEquals(
        lower,
        identity(
            plan,
            request(
                "GET",
                "example.com",
                "/render/a",
                "variant=x",
                "0".repeat(64),
                TrustLevel.UNTRUSTED),
            captures));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsIncompletePathIdentityAtStartup(boolean clientCompute) throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.KeyConfiguration missing =
        new ConfigurationModel.KeyConfiguration(List.of(), Optional.empty(), List.of());
    ConfigurationModel.KeyConfiguration unknown =
        new ConfigurationModel.KeyConfiguration(List.of("other"), Optional.empty(), List.of());

    ConfigurationException missingFailure =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(missing))));
    ConfigurationException unknownFailure =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(unknown))));

    assertTrue(missingFailure.getMessage().contains("must include variable capture 'id'"));
    assertTrue(unknownFailure.getMessage().contains("references unknown capture 'other'"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsMissingKeyAndSemanticQueryAliasesAtStartup(boolean clientCompute)
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.KeyConfiguration aliased =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of("a", "%61"), true)),
            List.of());

    ConfigurationException missing =
        assertThrows(ConfigurationException.class, () -> compile(withKey(base, Optional.empty())));
    ConfigurationException duplicate =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(aliased))));

    assertTrue(missing.getMessage().contains("key is required"));
    assertTrue(duplicate.getMessage().contains("semantically duplicate"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsInvalidQuerySelectorsAndRuntimeIdentityInputs(boolean clientCompute)
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute(clientCompute);
    ConfigurationModel.KeyConfiguration invalid =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of("a&b"), true)),
            List.of());

    ConfigurationException selector =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(invalid))));
    SemanticKeyPlan plan = compile(base);
    IllegalArgumentException digest =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                plan.operation(
                    request(
                        "GET",
                        "example.com",
                        "/render/a",
                        "variant=x",
                        "bad",
                        TrustLevel.UNTRUSTED),
                    Map.of("id", "a")));
    IllegalArgumentException capture =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                plan.operation(
                    request("GET", "example.com", "/render/%2f", "variant=x"),
                    Map.of("id", "%2f")));

    assertTrue(selector.getMessage().contains("invalid query name"));
    assertTrue(digest.getMessage().contains("64 hexadecimal"));
    assertTrue(capture.getMessage().contains("forbidden encoded value"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            plan.operation(
                request(
                    "GET",
                    "example.com",
                    "/render/a",
                    "variant=x",
                    "g".repeat(64),
                    TrustLevel.UNTRUSTED),
                Map.of("id", "a")));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queryNormalizationPreservesReservedEscapesAndEveryUnreservedBoundary(boolean clientCompute)
      throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute(clientCompute));
    Map<String, String> captures = Map.of("id", "a");
    String encoded = "variant=%61%7a%41%5a%30%39%2d%2e%5f%7e%40%5b%60%7b%0a";
    String canonical = "variant=azAZ09-._~%40%5B%60%7B%0A";
    assertEquals(
        identity(plan, request("GET", "example.com", "/render/a", encoded), captures),
        identity(plan, request("GET", "example.com", "/render/a", canonical), captures));
    assertNotEquals(
        identity(plan, request("GET", "example.com", "/render/a", canonical), captures),
        identity(
            plan,
            request("GET", "example.com", "/render/a", "variant=azAZ09-._~@[`{%0A"),
            captures));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selectedQueryNamesAndValuesAcceptVisibleAsciiEndpoints(boolean clientCompute)
      throws ConfigurationException {
    var key =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of("!", "~"), true)),
            List.of());
    SemanticKeyPlan plan = compile(withKey(renderRoute(clientCompute), Optional.of(key)));
    Map<String, String> captures = Map.of("id", "a");
    assertEquals(
        identity(plan, request("GET", "example.com", "/render/a", "!=!&~=~"), captures),
        identity(
            plan, request("GET", "example.com", "/render/a", "~=%7e&noise=ignored&!=!"), captures));
  }

  private static SemanticKeyPlan compile(ConfigurationModel.RouteConfiguration route)
      throws ConfigurationException {
    CompiledPathTemplate template =
        CompiledPathTemplate.compile(route.match().path(), "configuration.routes[0].match.path");
    return SemanticKeyPlan.compile(route, template.captureNames(), "configuration.routes[0]");
  }

  private static String identity(
      SemanticKeyPlan plan, RequestDescriptor request, Map<String, String> captures) {
    return plan.canonicalizer().canonicalize(plan.operation(request, captures));
  }

  private static ConfigurationModel.RouteConfiguration renderRoute(boolean clientCompute)
      throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    useClientComputation(fixture, clientCompute);
    return ConfigurationDecoder.decode(fixture.root()).routes().getFirst();
  }

  private static void useClientComputation(
      ConfigurationTestSupport.ObjectFixture fixture, boolean clientCompute) {
    if (clientCompute) {
      fixture.render().put("strategy", "CLIENT_COMPUTE");
      fixture.render().remove("representation");
      fixture.render().remove("budget");
      fixture.render().put("client-computation", fixture.clientComputation());
    }
  }

  private static ConfigurationModel.RouteConfiguration withKey(
      ConfigurationModel.RouteConfiguration route,
      Optional<ConfigurationModel.KeyConfiguration> key) {
    return new ConfigurationModel.RouteConfiguration(
        route.id(),
        route.version(),
        route.precedence(),
        route.match(),
        route.strategy(),
        key,
        route.materializerVersion(),
        route.budget(),
        route.clientComputation(),
        route.representation());
  }

  private static ConfigurationModel.RouteConfiguration withMatch(
      ConfigurationModel.RouteConfiguration route, ConfigurationModel.MatchConfiguration match) {
    return new ConfigurationModel.RouteConfiguration(
        route.id(),
        route.version(),
        route.precedence(),
        match,
        route.strategy(),
        route.key(),
        route.materializerVersion(),
        route.budget(),
        route.clientComputation(),
        route.representation());
  }

  private static RequestDescriptor request(String method, String host, String path, String query) {
    return request(method, host, path, query, BODY_A, TrustLevel.UNTRUSTED);
  }

  private static RequestDescriptor request(
      String method, String host, String path, String query, String bodySha256, TrustLevel trust) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of(method));
    attributes.put("host", List.of(host));
    attributes.put("path", List.of(path));
    if (query != null) {
      attributes.put("query", List.of(query));
    }
    attributes.put("body-sha256", List.of(bodySha256));
    return new RequestDescriptor("http.request", attributes, trust);
  }
}
