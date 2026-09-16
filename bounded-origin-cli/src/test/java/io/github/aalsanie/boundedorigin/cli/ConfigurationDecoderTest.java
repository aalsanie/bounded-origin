package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConfigurationDecoderTest {
  @Test
  void decodesStructuredConfiguration() throws Exception {
    var fixture = ConfigurationTestSupport.objectFixture();

    var configuration = ConfigurationDecoder.decode(fixture.root());

    assertEquals(1, configuration.schema());
    assertEquals("origin.internal", configuration.gateway().get("origin.host"));
    assertEquals(2, configuration.routes().size());
    assertEquals("render", configuration.routes().getFirst().id());
    assertEquals("client", configuration.routes().get(1).id());
    assertEquals("default-deny", configuration.fallback().id());
  }

  @Test
  void rejectsUnknownKeysAtEverySchemaBoundary() {
    assertMutation(
        fixture -> fixture.root().put("unknown", true),
        "configuration contains unknown key unknown");
    assertMutation(
        fixture -> fixture.gateway().put("unknown", true),
        "configuration.gateway contains unknown key unknown");
    assertMutation(
        fixture -> fixture.store().put("unknown", true),
        "configuration.store contains unknown key unknown");
    assertMutation(
        fixture -> fixture.render().put("unknown", true),
        "configuration.routes[0] contains unknown key unknown");
    assertMutation(
        fixture -> fixture.renderMatch().put("unknown", true),
        "configuration.routes[0].match contains unknown key unknown");
    assertMutation(
        fixture -> fixture.renderKey().put("unknown", true),
        "configuration.routes[0].key contains unknown key unknown");
    assertMutation(
        fixture -> fixture.renderQuery().put("unknown", true),
        "configuration.routes[0].key.query contains unknown key unknown");
    assertMutation(
        fixture -> fixture.renderBudget().put("unknown", true),
        "configuration.routes[0].budget contains unknown key unknown");
    assertMutation(
        fixture -> fixture.clientComputation().put("unknown", true),
        "configuration.routes[1].client-computation contains unknown key unknown");
    assertMutation(
        fixture -> fixture.fallback().put("unknown", true),
        "configuration.fallback contains unknown key unknown");
  }

  @Test
  void rejectsMissingRootAndGatewayRequirements() {
    assertMutation(fixture -> fixture.root().remove("schema"), "configuration.schema is required");
    assertMutation(
        fixture -> fixture.root().put("gateway", null), "configuration.gateway must not be null");
    assertMutation(
        fixture -> fixture.gateway().remove("origin.host"),
        "configuration.gateway.origin.host is required");
    assertMutation(
        fixture -> fixture.gateway().remove("origin.port"),
        "configuration.gateway.origin.port is required");
    assertMutation(
        fixture -> fixture.gateway().remove("temporary.directory"),
        "configuration.gateway.temporary.directory is required");
  }

  @Test
  void rejectsMissingStoreRequirements() {
    assertMutation(
        fixture -> fixture.root().put("store", null), "configuration.store must not be null");
    assertMutation(
        fixture -> fixture.store().remove("directory"),
        "configuration.store.directory is required");
    assertMutation(
        fixture -> fixture.store().remove("max-bytes"),
        "configuration.store.max-bytes is required");
    assertMutation(
        fixture -> fixture.store().remove("max-artifact-bytes"),
        "configuration.store.max-artifact-bytes is required");
  }

  @Test
  void rejectsMissingRouteRequirements() {
    assertMutation(
        fixture -> fixture.root().put("routes", null), "configuration.routes must not be null");
    assertMutation(
        fixture -> fixture.render().remove("id"), "configuration.routes[0].id is required");
    assertMutation(
        fixture -> fixture.render().remove("version"),
        "configuration.routes[0].version is required");
    assertMutation(
        fixture -> fixture.render().remove("precedence"),
        "configuration.routes[0].precedence is required");
    assertMutation(
        fixture -> fixture.render().put("match", null),
        "configuration.routes[0].match must not be null");
    assertMutation(
        fixture -> fixture.renderMatch().remove("path"),
        "configuration.routes[0].match.path is required");
    assertMutation(
        fixture -> fixture.render().remove("strategy"),
        "configuration.routes[0].strategy is required");
  }

  @Test
  void rejectsMissingFallbackRequirements() {
    assertMutation(
        fixture -> fixture.root().put("fallback", null), "configuration.fallback must not be null");
    assertMutation(
        fixture -> fixture.fallback().remove("id"), "configuration.fallback.id is required");
    assertMutation(
        fixture -> fixture.fallback().remove("version"),
        "configuration.fallback.version is required");
    assertMutation(
        fixture -> fixture.fallback().remove("precedence"),
        "configuration.fallback.precedence is required");
    assertMutation(
        fixture -> fixture.fallback().remove("strategy"),
        "configuration.fallback.strategy is required");
  }

  @Test
  void rejectsWrongContainerTypes() {
    assertDecodeFailure(List.of(), "configuration must be a mapping");
    assertMutation(
        fixture -> fixture.root().put("gateway", List.of()),
        "configuration.gateway must be a mapping");
    assertMutation(
        fixture -> fixture.root().put("store", List.of()), "configuration.store must be a mapping");
    assertMutation(
        fixture -> fixture.root().put("routes", fixture.gateway()),
        "configuration.routes must be a sequence");
    assertMutation(
        fixture -> fixture.root().put("fallback", List.of()),
        "configuration.fallback must be a mapping");
    assertMutation(
        fixture -> fixture.routes().set(0, List.of()), "configuration.routes[0] must be a mapping");
    assertMutation(
        fixture -> fixture.render().put("match", List.of()),
        "configuration.routes[0].match must be a mapping");
    assertMutation(
        fixture -> fixture.render().put("key", List.of()),
        "configuration.routes[0].key must be a mapping");
    assertMutation(
        fixture -> fixture.renderKey().put("query", List.of()),
        "configuration.routes[0].key.query must be a mapping");
    assertMutation(
        fixture -> fixture.render().put("budget", List.of()),
        "configuration.routes[0].budget must be a mapping");
    assertMutation(
        fixture -> fixture.client().put("client-computation", List.of()),
        "configuration.routes[1].client-computation must be a mapping");
  }

  @Test
  void rejectsUnsupportedSchemaAndUnsafeFallback() {
    assertMutation(fixture -> fixture.root().put("schema", 2), "configuration.schema must be 1");
    assertMutation(
        fixture -> fixture.fallback().put("strategy", "MATERIALIZE"),
        "configuration.fallback.strategy must be DENY");
  }

  @Test
  void rejectsDuplicatePolicyIds() {
    assertMutation(fixture -> fixture.fallback().put("id", "render"), "duplicate policy id render");
    assertMutation(fixture -> fixture.client().put("id", "render"), "duplicate policy id render");
  }

  @Test
  void acceptsSignedPrecedenceBounds() throws Exception {
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.render().put("precedence", Integer.MAX_VALUE);
    fixture.fallback().put("precedence", Integer.MIN_VALUE);

    var configuration = ConfigurationDecoder.decode(fixture.root());

    assertEquals(Integer.MAX_VALUE, configuration.routes().getFirst().precedence());
    assertEquals(Integer.MIN_VALUE, configuration.fallback().precedence());
  }

  private static void assertMutation(
      Consumer<ConfigurationTestSupport.ObjectFixture> mutation, String expected) {
    var fixture = ConfigurationTestSupport.objectFixture();
    mutation.accept(fixture);
    assertDecodeFailure(fixture.root(), expected);
  }

  private static void assertDecodeFailure(Object value, String expected) {
    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> ConfigurationDecoder.decode(value));
    assertTrue(
        exception.getMessage().contains(expected),
        () -> "expected <" + expected + "> in <" + exception.getMessage() + ">");
  }
}
