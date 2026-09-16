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
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticKeyPlanBoundaryTest {
  private static final String SHA = "a".repeat(64);

  @Test
  void rejectsReachableMalformedConfigurationShapes() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = route();
    String path = "configuration.routes[0]";

    assertFailure(
        () -> SemanticKeyPlan.compile(withKey(base, null), Set.of("id"), path),
        "key is required");
    assertFailure(
        () ->
            SemanticKeyPlan.compile(
                withKey(
                    base,
                    Optional.of(
                        new ConfigurationModel.KeyConfiguration(
                            List.of("id", "id"), Optional.empty()))),
                Set.of("id"),
                path),
        "duplicate capture names");
    assertFailure(
        () -> SemanticKeyPlan.compile(withMatch(base, null), Set.of("id"), path),
        "match must not be null");
    assertFailure(
        () ->
            SemanticKeyPlan.compile(
                withMatch(
                    base,
                    new ConfigurationModel.MatchConfiguration(
                        null, Optional.empty(), "/render/{id}", Optional.empty())),
                Set.of("id"),
                path),
        "optional constraints must not be null");
    assertFailure(
        () ->
            SemanticKeyPlan.compile(
                withMatch(
                    base,
                    new ConfigurationModel.MatchConfiguration(
                        Optional.empty(), Optional.empty(), null, Optional.empty())),
                Set.of("id"),
                path),
        "match.path must not be blank");
    assertFailure(
        () ->
            SemanticKeyPlan.compile(
                withMatch(
                    base,
                    new ConfigurationModel.MatchConfiguration(
                        Optional.empty(), Optional.empty(), " ", Optional.empty())),
                Set.of("id"),
                path),
        "match.path must not be blank");
  }

  @Test
  void rejectsEveryConstructibleInvalidConfiguredQueryNameForm() throws ConfigurationException {
    assertInvalidSelector(" ", "blank query name");
    assertInvalidSelector("a&b", "invalid query name");
    assertInvalidSelector("a=b", "invalid query name");
    assertInvalidSelector("a#b", "invalid query name");
    assertInvalidSelector("a\nb", "invalid query name");
    assertInvalidSelector("é", "invalid query name");
    assertInvalidSelector("%", "invalid query name");
    assertInvalidSelector("%GG", "invalid query name");
  }

  @Test
  void rejectsMalformedRuntimeAttributesAndCaptures() throws ConfigurationException {
    SemanticKeyPlan plan = compile(route());

    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(List.of("x=1", "x=2"), null, List.of(SHA)), Map.of("id", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(null, null, List.of(SHA)), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(null, null, List.of(SHA)), Map.of("id", "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(List.of("variant=%"), null, List.of(SHA)), Map.of("id", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(List.of("variant=%GG"), null, List.of(SHA)), Map.of("id", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(List.of("variant=a b"), null, List.of(SHA)), Map.of("id", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(null, null, List.of("z".repeat(64))), Map.of("id", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(null, null, List.of(SHA, SHA)), Map.of("id", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(null, null, List.of(SHA)), Map.of("id", "%5c")));
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.operation(request(null, null, List.of(SHA)), Map.of("id", "%00")));
  }

  @Test
  void selectedQueryMayBeAbsentWithoutChangingUnselectedIdentity() throws ConfigurationException {
    SemanticKeyPlan plan = compile(route());

    String absent = identity(plan, request(null, null, List.of(SHA)), Map.of("id", "a"));
    String ignored =
        identity(plan, request(List.of("ignored=x"), null, List.of(SHA)), Map.of("id", "a"));

    assertEquals(absent, ignored);
  }

  @Test
  void normalizesAllUnreservedPercentEncodedClasses() throws ConfigurationException {
    SemanticKeyPlan plan = compile(route());

    String encoded =
        identity(
            plan,
            request(List.of("variant=%41%30%2D%2E%5F%7E"), null, List.of(SHA)),
            Map.of("id", "%41"));
    String literal =
        identity(
            plan,
            request(List.of("variant=A0-._~"), null, List.of(SHA)),
            Map.of("id", "A"));

    assertEquals(encoded, literal);
  }

  @Test
  void exactRootCatchAllIncludesFullPathAndRawQueryMayBeAbsent() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = route();
    ConfigurationModel.RouteConfiguration catchAll =
        withMatch(
            withKey(
                base,
                Optional.of(
                    new ConfigurationModel.KeyConfiguration(List.of(), Optional.empty()))),
            new ConfigurationModel.MatchConfiguration(
                Optional.of("GET"), Optional.of("example.com"), "/**", Optional.empty()));
    SemanticKeyPlan plan = SemanticKeyPlan.compile(catchAll, Set.of(), "configuration.routes[0]");

    String first = identity(plan, request(null, "/a", List.of(SHA)), Map.of());
    String second = identity(plan, request(null, "/b", List.of(SHA)), Map.of());

    assertNotEquals(first, second);
  }

  @Test
  void nullCompilationInputsAreRejected() throws ConfigurationException {
    ConfigurationModel.RouteConfiguration route = route();

    assertThrows(
        NullPointerException.class,
        () -> SemanticKeyPlan.compile(null, Set.of("id"), "configuration.routes[0]"));
    assertThrows(
        NullPointerException.class,
        () -> SemanticKeyPlan.compile(route, null, "configuration.routes[0]"));
    assertThrows(
        NullPointerException.class, () -> SemanticKeyPlan.compile(route, Set.of("id"), null));
  }

  private static void assertInvalidSelector(String selector, String expected)
      throws ConfigurationException {
    ConfigurationModel.RouteConfiguration base = route();
    ConfigurationModel.KeyConfiguration key =
        new ConfigurationModel.KeyConfiguration(
            List.of("id"),
            Optional.of(new ConfigurationModel.QueryKeyConfiguration(List.of(selector), false)));
    assertFailure(
        () ->
            SemanticKeyPlan.compile(
                withKey(base, Optional.of(key)), Set.of("id"), "configuration.routes[0]"),
        expected);
  }

  private static SemanticKeyPlan compile(ConfigurationModel.RouteConfiguration route)
      throws ConfigurationException {
    return SemanticKeyPlan.compile(route, Set.of("id"), "configuration.routes[0]");
  }

  private static String identity(
      SemanticKeyPlan plan, RequestDescriptor request, Map<String, String> captures) {
    return plan.canonicalizer().canonicalize(plan.operation(request, captures));
  }

  private static ConfigurationModel.RouteConfiguration route() throws ConfigurationException {
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

  private static RequestDescriptor request(
      List<String> query, String path, List<String> bodyShaValues) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("host", List.of("example.com"));
    attributes.put("path", List.of(path == null ? "/render/a" : path));
    if (query != null) {
      attributes.put("query", query);
    }
    attributes.put("body-sha256", bodyShaValues);
    return new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);
  }

  private static void assertFailure(ThrowingOperation operation, String expected) {
    ConfigurationException failure = assertThrows(ConfigurationException.class, operation::run);
    assertTrue(failure.getMessage().contains(expected));
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run() throws ConfigurationException;
  }
}
