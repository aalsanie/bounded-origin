package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteTemplateCompilerTest {
  @Test
  void matchesLiteralCaptureRootAndTerminalCatchAll() throws ConfigurationException {
    CompiledRouteTable table =
        RouteTemplateCompiler.compile(
            List.of(
                RouteTemplateTestSupport.route("capture", 100, "/render/{id}"),
                RouteTemplateTestSupport.route("assets", 90, "/assets/**"),
                RouteTemplateTestSupport.route("root", 80, "/")));

    CompiledRouteTable.Match capture =
        table
            .match("GET", "example.com", "/render/%41", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow();
    assertEquals("capture", capture.route().id());
    assertEquals(Map.of("id", "%41"), capture.pathCaptures());
    assertThrows(UnsupportedOperationException.class, () -> capture.pathCaptures().put("x", "y"));

    assertEquals(
        "assets",
        table
            .match("GET", "example.com", "/assets", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
    assertEquals(
        "assets",
        table
            .match("GET", "example.com", "/assets/", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
    assertEquals(
        "assets",
        table
            .match("GET", "example.com", "/assets/a/b", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
    assertEquals(
        "root",
        table
            .match("GET", "example.com", "/", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
    assertTrue(
        table.match("OPTIONS", "example.com", "*", ConfigurationModel.Trust.UNTRUSTED).isEmpty());
    assertTrue(
        table
            .match("GET", "example.com", "/render/", ConfigurationModel.Trust.UNTRUSTED)
            .isEmpty());
  }

  @Test
  void appliesMethodHostAndTrustConstraints() throws ConfigurationException {
    CompiledRouteTable table =
        RouteTemplateCompiler.compile(
            List.of(
                RouteTemplateTestSupport.route(
                    "strict",
                    100,
                    "/secure/{id}",
                    "GET",
                    "Example.COM:8443",
                    ConfigurationModel.Trust.TRUSTED)));

    assertEquals(
        "strict",
        table
            .match("GET", "EXAMPLE.COM:8443", "/secure/7", ConfigurationModel.Trust.TRUSTED)
            .orElseThrow()
            .route()
            .id());
    assertTrue(
        table
            .match("get", "example.com:8443", "/secure/7", ConfigurationModel.Trust.TRUSTED)
            .isEmpty());
    assertTrue(
        table
            .match("GET", "other.com:8443", "/secure/7", ConfigurationModel.Trust.TRUSTED)
            .isEmpty());
    assertTrue(
        table
            .match("GET", "example.com:8443", "/secure/7", ConfigurationModel.Trust.UNTRUSTED)
            .isEmpty());
  }

  @Test
  void higherPrecedenceWinsIndependentOfFileOrder() throws ConfigurationException {
    var broad = RouteTemplateTestSupport.route("broad", 10, "/render/{id}");
    var specific = RouteTemplateTestSupport.route("specific", 20, "/render/special");

    CompiledRouteTable first = RouteTemplateCompiler.compile(List.of(broad, specific));
    CompiledRouteTable second = RouteTemplateCompiler.compile(List.of(specific, broad));

    assertEquals(
        "specific",
        first
            .match("GET", "example.com", "/render/special", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
    assertEquals(
        "specific",
        second
            .match("GET", "example.com", "/render/special", ConfigurationModel.Trust.UNTRUSTED)
            .orElseThrow()
            .route()
            .id());
  }

  @Test
  void supportsEncodedLiteralAsteriskWithoutTreatingItAsWildcard() throws ConfigurationException {
    CompiledRouteTable table =
        RouteTemplateCompiler.compile(
            List.of(RouteTemplateTestSupport.route("literal", 1, "/a/%2A")));

    assertTrue(
        table
            .match("GET", "example.com", "/a/%2A", ConfigurationModel.Trust.UNTRUSTED)
            .isPresent());
    assertFalse(
        table
            .match("GET", "example.com", "/a/value", ConfigurationModel.Trust.UNTRUSTED)
            .isPresent());
  }

  @Test
  void emptyRouteTableNeverMatches() throws ConfigurationException {
    CompiledRouteTable table = RouteTemplateCompiler.compile(List.of());
    assertTrue(
        table.match("GET", "example.com", "/x", ConfigurationModel.Trust.UNTRUSTED).isEmpty());
  }

  @Test
  void runtimeMatchArgumentsAreRequired() throws ConfigurationException {
    CompiledRouteTable table =
        RouteTemplateCompiler.compile(List.of(RouteTemplateTestSupport.route("route", 1, "/x")));
    assertThrows(
        NullPointerException.class,
        () -> table.match(null, "example.com", "/x", ConfigurationModel.Trust.UNTRUSTED));
    assertThrows(
        NullPointerException.class,
        () -> table.match("GET", null, "/x", ConfigurationModel.Trust.UNTRUSTED));
    assertThrows(
        NullPointerException.class,
        () -> table.match("GET", "example.com", null, ConfigurationModel.Trust.UNTRUSTED));
    assertThrows(NullPointerException.class, () -> table.match("GET", "example.com", "/x", null));
  }
}
