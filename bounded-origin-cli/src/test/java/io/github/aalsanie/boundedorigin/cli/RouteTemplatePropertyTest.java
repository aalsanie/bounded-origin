package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTemplatePropertyTest {
  @Test
  void generatedSingleSegmentCapturesRoundTripRawValues() throws ConfigurationException {
    for (int index = 0; index < 200; index++) {
      String prefix = "p" + ((index * 37) % 10_000);
      String suffix = "s" + ((index * 101) % 10_000);
      String value = "v" + ((index * 211) % 10_000);
      String template = "/" + prefix + "/{value}/" + suffix;
      String path = "/" + prefix + "/" + value + "/" + suffix;
      CompiledRouteTable table =
          RouteTemplateCompiler.compile(
              List.of(RouteTemplateTestSupport.route("route", 1, template)));

      CompiledRouteTable.Match match =
          table
              .match("GET", "example.com", path, ConfigurationModel.Trust.UNTRUSTED)
              .orElseThrow();
      assertEquals(value, match.pathCaptures().get("value"));
      assertTrue(
          table
              .match(
                  "GET",
                  "example.com",
                  "/wrong/" + value + "/" + suffix,
                  ConfigurationModel.Trust.UNTRUSTED)
              .isEmpty());
    }
  }

  @Test
  void routeSelectionIsStableAcrossGeneratedFileOrderPermutations()
      throws ConfigurationException {
    List<ConfigurationModel.RouteConfiguration> routes = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      routes.add(
          RouteTemplateTestSupport.route("route-" + index, index, "/r" + index + "/{id}"));
    }

    for (int seed = 0; seed < 20; seed++) {
      List<ConfigurationModel.RouteConfiguration> shuffled = new ArrayList<>(routes);
      Collections.rotate(shuffled, seed);
      if ((seed & 1) != 0) {
        Collections.reverse(shuffled);
      }
      CompiledRouteTable table = RouteTemplateCompiler.compile(shuffled);
      for (int index = 0; index < routes.size(); index++) {
        assertEquals(
            "route-" + index,
            table
                .match(
                    "GET",
                    "example.com",
                    "/r" + index + "/value",
                    ConfigurationModel.Trust.UNTRUSTED)
                .orElseThrow()
                .route()
                .id());
      }
    }
  }
}
