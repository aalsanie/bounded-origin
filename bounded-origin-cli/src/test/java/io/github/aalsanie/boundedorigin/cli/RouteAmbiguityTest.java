package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RouteAmbiguityTest {
  @Test
  void rejectsSamePrecedencePathOverlaps() {
    assertAmbiguous("/x/{id}", "/x/fixed");
    assertAmbiguous("/x/fixed", "/x/{id}");
    assertAmbiguous("/x/{left}", "/x/{right}");
    assertAmbiguous("/x/**", "/x");
    assertAmbiguous("/x/**", "/x/a/b");
    assertAmbiguous("/x/a/b", "/x/**");
    assertAmbiguous("/**", "/");
    assertAmbiguous("/a/{id}/**", "/a/value/tail/**");
  }

  @Test
  void allowsSamePrecedenceRoutesWhenAConstraintMakesThemDisjoint()
      throws ConfigurationException {
    RouteTemplateCompiler.compile(
        List.of(
            RouteTemplateTestSupport.route("get", 10, "/x/{id}", "GET", null, null),
            RouteTemplateTestSupport.route("post", 10, "/x/{id}", "POST", null, null)));
    RouteTemplateCompiler.compile(
        List.of(
            RouteTemplateTestSupport.route("a", 10, "/x/{id}", null, "a.example", null),
            RouteTemplateTestSupport.route("b", 10, "/x/{id}", null, "b.example", null)));
    RouteTemplateCompiler.compile(
        List.of(
            RouteTemplateTestSupport.route(
                "trusted", 10, "/x/{id}", null, null, ConfigurationModel.Trust.TRUSTED),
            RouteTemplateTestSupport.route(
                "untrusted", 10, "/x/{id}", null, null, ConfigurationModel.Trust.UNTRUSTED)));
    RouteTemplateCompiler.compile(
        List.of(
            RouteTemplateTestSupport.route("short", 10, "/x"),
            RouteTemplateTestSupport.route("long", 10, "/x/y")));
    RouteTemplateCompiler.compile(
        List.of(
            RouteTemplateTestSupport.route("left", 10, "/a/**"),
            RouteTemplateTestSupport.route("right", 10, "/b/**")));
  }

  @Test
  void missingConstraintOverlapsSpecificConstraint() {
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(
                    RouteTemplateTestSupport.route("all", 10, "/x"),
                    RouteTemplateTestSupport.route("get", 10, "/x", "GET", null, null))));
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(
                    RouteTemplateTestSupport.route("all", 10, "/x"),
                    RouteTemplateTestSupport.route(
                        "host", 10, "/x", null, "Example.COM", null))));
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(
                    RouteTemplateTestSupport.route("all", 10, "/x"),
                    RouteTemplateTestSupport.route(
                        "trust", 10, "/x", null, null, ConfigurationModel.Trust.TRUSTED))));
  }

  @Test
  void hostComparisonForAmbiguityIsCaseInsensitive() {
    assertThrows(
        ConfigurationException.class,
        () ->
            RouteTemplateCompiler.compile(
                List.of(
                    RouteTemplateTestSupport.route(
                        "a", 10, "/x", null, "EXAMPLE.COM", null),
                    RouteTemplateTestSupport.route(
                        "b", 10, "/x", null, "example.com", null))));
  }

  @Test
  void methodComparisonForAmbiguityRemainsCaseSensitive() throws ConfigurationException {
    RouteTemplateCompiler.compile(
        List.of(
            RouteTemplateTestSupport.route("upper", 10, "/x", "GET", null, null),
            RouteTemplateTestSupport.route("lower", 10, "/x", "get", null, null)));
  }

  @Test
  void overlappingRoutesAtDifferentPrecedenceAreAllowed() throws ConfigurationException {
    CompiledRouteTable table =
        RouteTemplateCompiler.compile(
            List.of(
                RouteTemplateTestSupport.route("broad", 1, "/x/{id}"),
                RouteTemplateTestSupport.route("specific", 2, "/x/fixed")));
    assertEquals(
        "specific",
        table
            .match("GET", "example.com", "/x/fixed", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
  }

  @Test
  void ambiguityFailureIsIndependentOfFileOrder() {
    List<ConfigurationModel.RouteConfiguration> routes =
        List.of(
            RouteTemplateTestSupport.route("c", 10, "/x/{c}"),
            RouteTemplateTestSupport.route("a", 10, "/x/{a}"),
            RouteTemplateTestSupport.route("b", 10, "/x/{b}"));
    String expected =
        "configuration.routes contains ambiguous same-precedence overlap between 'a' and 'b'";

    for (int seed = 0; seed < 20; seed++) {
      List<ConfigurationModel.RouteConfiguration> shuffled = new ArrayList<>(routes);
      Collections.shuffle(shuffled, new Random(seed));
      ConfigurationException failure =
          assertThrows(ConfigurationException.class, () -> RouteTemplateCompiler.compile(shuffled));
      assertEquals(expected, failure.getMessage());
    }
  }

  private static void assertAmbiguous(String left, String right) {
    ConfigurationException failure =
        assertThrows(
            ConfigurationException.class,
            () ->
                RouteTemplateCompiler.compile(
                    List.of(
                        RouteTemplateTestSupport.route("a", 10, left),
                        RouteTemplateTestSupport.route("b", 10, right))));
    assertTrue(failure.getMessage().contains("ambiguous same-precedence overlap"));
  }
}
