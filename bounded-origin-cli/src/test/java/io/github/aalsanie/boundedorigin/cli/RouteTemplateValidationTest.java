package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteTemplateValidationTest {
  @Test
  void rejectsMalformedPathTemplates() {
    List<String> malformed =
        List.of(
            "",
            " ",
            "relative",
            "/trailing/",
            "/double//slash",
            "/a/**/b",
            "/a/*",
            "/a/x*y",
            "/a/{id",
            "/a/id}",
            "/a/{}",
            "/a/{1id}",
            "/a/{id.name}",
            "/a/{id}/{id}",
            "/a?x=1",
            "/a#fragment",
            "/a\\b",
            "/a b",
            "/café",
            "/a/%",
            "/a/%2",
            "/a/%GG",
            "/a/%2F",
            "/a/%5c",
            "/a/%00",
            "/a/.",
            "/a/..",
            "/a/%2e",
            "/a/%2E%2e");

    for (String path : malformed) {
      assertThrows(
          ConfigurationException.class,
          () -> RouteTemplateCompiler.compile(List.of(RouteTemplateTestSupport.route("x", 1, path))),
          path);
    }
  }

  @Test
  void rejectsNullPathAndExcessiveSegmentCount() throws ConfigurationException {
    assertThrows(
        ConfigurationException.class,
        () -> RouteTemplateCompiler.compile(List.of(RouteTemplateTestSupport.route("x", 1, null))));

    String maximum = "/x".repeat(ConfigurationLimits.MAX_ROUTE_SEGMENTS);
    RouteTemplateCompiler.compile(List.of(RouteTemplateTestSupport.route("max", 1, maximum)));
    String tooMany = "/x".repeat(ConfigurationLimits.MAX_ROUTE_SEGMENTS + 1);
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () ->
                RouteTemplateCompiler.compile(
                    List.of(RouteTemplateTestSupport.route("x", 1, tooMany))));
    assertTrue(failure.getMessage().contains("maximum segment count"));
  }

  @Test
  void validatesMethodConstraints() throws ConfigurationException {
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(RouteTemplateTestSupport.route("x", 1, "/x", " ", null, null))));
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(RouteTemplateTestSupport.route("x", 1, "/x", "GE T", null, null))));

    CompiledRouteTable table =
        RouteTemplateCompiler.compile(
            List.of(RouteTemplateTestSupport.route("x", 1, "/x", "X-CUSTOM", null, null)));
    assertTrue(
        table
            .match("X-CUSTOM", "example.com", "/x", ConfigurationModel.Trust.UNTRUSTED)
            .isPresent());
  }

  @Test
  void validatesAndNormalizesHostConstraints() throws ConfigurationException {
    for (String invalid :
        List.of(
            " ",
            "café",
            "bad host",
            "bad/host",
            "bad\\host",
            "bad?host",
            "bad#host",
            "a@b",
            "a,b",
            "*.example.com")) {
      assertThrows(
          ConfigurationException.class,
          () ->
              RouteTemplateCompiler.compile(
                  List.of(RouteTemplateTestSupport.route("x", 1, "/x", null, invalid, null))),
          invalid);
    }

    CompiledRouteTable table =
        RouteTemplateCompiler.compile(
            List.of(RouteTemplateTestSupport.route("x", 1, "/x", null, "Example.COM", null)));
    assertTrue(
        table.match("GET", "EXAMPLE.COM", "/x", ConfigurationModel.Trust.UNTRUSTED).isPresent());
  }

  @Test
  void rejectsInvalidTypedRouteInputs() {
    assertThrows(ConfigurationException.class, () -> RouteTemplateCompiler.compile(null));
    List<ConfigurationModel.RouteConfiguration> nullRoute = new ArrayList<>();
    nullRoute.add(null);
    assertThrows(ConfigurationException.class, () -> RouteTemplateCompiler.compile(nullRoute));
    assertThrows(
        ConfigurationException.class,
        () -> RouteTemplateCompiler.compile(List.of(RouteTemplateTestSupport.route(" ", 1, "/x"))));
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(RouteTemplateTestSupport.routeWithMatch("x", 1, null))));

    ConfigurationModel.MatchConfiguration nullMethod =
        new ConfigurationModel.MatchConfiguration(null, Optional.empty(), "/x", Optional.empty());
    ConfigurationModel.MatchConfiguration nullHost =
        new ConfigurationModel.MatchConfiguration(Optional.empty(), null, "/x", Optional.empty());
    ConfigurationModel.MatchConfiguration nullTrust =
        new ConfigurationModel.MatchConfiguration(Optional.empty(), Optional.empty(), "/x", null);
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(RouteTemplateTestSupport.routeWithMatch("x", 1, nullMethod))));
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(RouteTemplateTestSupport.routeWithMatch("x", 1, nullHost))));
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(RouteTemplateTestSupport.routeWithMatch("x", 1, nullTrust))));
  }

  @Test
  void enforcesRouteCountAndDuplicateIds() throws ConfigurationException {
    List<ConfigurationModel.RouteConfiguration> routes = new ArrayList<>();
    for (int index = 0; index <= ConfigurationLimits.MAX_ROUTES; index++) {
      routes.add(RouteTemplateTestSupport.route("r" + index, index, "/r" + index));
      if (index == ConfigurationLimits.MAX_ROUTES - 1) {
        RouteTemplateCompiler.compile(routes);
      }
    }
    assertThrows(ConfigurationException.class, () -> RouteTemplateCompiler.compile(routes));

    ConfigurationException duplicate =
        assertThrows(
            ConfigurationException.class,
            () ->
                RouteTemplateCompiler.compile(
                    List.of(
                        RouteTemplateTestSupport.route("same", 2, "/a"),
                        RouteTemplateTestSupport.route("same", 1, "/b"))));
    assertEquals("configuration.routes contains duplicate id 'same'", duplicate.getMessage());
  }
}
