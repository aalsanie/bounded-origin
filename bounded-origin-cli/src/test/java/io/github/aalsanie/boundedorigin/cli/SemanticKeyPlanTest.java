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
import org.junit.jupiter.api.Test;

class SemanticKeyPlanTest {
  private static final String BODY_A = "a".repeat(64);
  private static final String BODY_B = "b".repeat(64);

  @Test
  void canonicalizesSelectedQueryPairsAndPreservesMultiplicity() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route = renderRoute();
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

  @Test
  void distinguishesMissingAndEmptySelectedQueryValues() throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute());

    String missingEquals =
        identity(plan, request("GET", "example.com", "/render/a", "variant"), Map.of("id", "a"));
    String emptyValue =
        identity(plan, request("GET", "example.com", "/render/a", "variant="), Map.of("id", "a"));

    assertNotEquals(missingEquals, emptyValue);
  }

  @Test
  void treatsPlusAsLiteralAndNormalizesOnlyUnreservedPercentEncodings()
      throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute());

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

  @Test
  void preservesRawQueryWhenSelectedModeIsAbsent() throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
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

  @Test
  void explicitEmptySelectedQueryIgnoresQuery() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of(), false)));
    ConfigurationModel.RouteConfiguration route = withKey(base, Optional.of(key));
    SemanticKeyPlan plan = compile(route);

    String first =
        identity(plan, request("GET", "example.com", "/render/a", "x=1"), Map.of("id", "a"));
    String second =
        identity(plan, request("GET", "example.com", "/render/a", "x=2"), Map.of("id", "a"));

    assertEquals(first, second);
  }

  @Test
  void orderedSelectedQueryPreservesPairOrder() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(
                new ConfigurationModel.QueryKeyConfiguration(List.of("variant", "format"), false)));
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

  @Test
  void wildcardRouteConstraintsParticipateInIdentity() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
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

  @Test
  void catchAllRouteIncludesFullPath() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
    ConfigurationModel.MatchConfiguration catchAll =
        new ConfigurationModel.MatchConfiguration(
            Optional.of("GET"), Optional.of("example.com"), "/render/**", Optional.empty());
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(List.of(), Optional.empty());
    SemanticKeyPlan plan = compile(withKey(withMatch(base, catchAll), Optional.of(key)));

    String first = identity(plan, request("GET", "example.com", "/render/a", null), Map.of());
    String second = identity(plan, request("GET", "example.com", "/render/b", null), Map.of());

    assertNotEquals(first, second);
  }

  @Test
  void bodyDigestAlwaysParticipatesAndIsCaseNormalized() throws ConfigurationException {
    SemanticKeyPlan plan = compile(renderRoute());
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
  }

  @Test
  void rejectsIncompletePathIdentityAtStartup() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
    ConfigurationModel.KeyConfiguration missing =
        new ConfigurationModel.KeyConfiguration(List.of(), Optional.empty());
    ConfigurationModel.KeyConfiguration unknown =
        new ConfigurationModel.KeyConfiguration(List.of("other"), Optional.empty());

    ConfigurationException missingFailure =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(missing))));
    ConfigurationException unknownFailure =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(unknown))));

    assertTrue(missingFailure.getMessage().contains("must include variable capture 'id'"));
    assertTrue(unknownFailure.getMessage().contains("references unknown capture 'other'"));
  }

  @Test
  void rejectsMissingKeyAndSemanticQueryAliasesAtStartup() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
    ConfigurationModel.KeyConfiguration aliased =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of("a", "%61"), true)));

    ConfigurationException missing =
        assertThrows(ConfigurationException.class, () -> compile(withKey(base, Optional.empty())));
    ConfigurationException duplicate =
        assertThrows(
            ConfigurationException.class, () -> compile(withKey(base, Optional.of(aliased))));

    assertTrue(missing.getMessage().contains("key is required"));
    assertTrue(duplicate.getMessage().contains("semantically duplicate"));
  }

  @Test
  void rejectsInvalidQuerySelectorsAndRuntimeIdentityInputs() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = renderRoute();
    ConfigurationModel.KeyConfiguration invalid =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of("a&b"), true)));

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

  private static ConfigurationModel.RouteConfiguration renderRoute() throws ConfigurationException {
    return ConfigurationDecoder.decode(ConfigurationTestSupport.objectFixture().root())
        .routes()
        .getFirst();
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
        route.clientComputation());
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
        route.clientComputation());
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
