package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConfigurationBoundaryTest {
  @Test
  void rejectsInvalidNumericBounds() {
    assertMutation(
        fixture -> fixture.render().put("version", -1L),
        "configuration.routes[0].version must be non-negative");
    assertMutation(
        fixture -> fixture.render().put("version", "9223372036854775808"),
        "configuration.routes[0].version must be a integer");
    assertMutation(
        fixture -> fixture.render().put("precedence", (long) Integer.MAX_VALUE + 1L),
        "outside the signed 32-bit range");
    assertMutation(
        fixture -> fixture.store().put("max-bytes", 0L),
        "configuration.store.max-bytes must be positive");
    assertMutation(
        fixture -> fixture.store().put("max-artifact-bytes", -1L),
        "configuration.store.max-artifact-bytes must be non-negative");
    assertMutation(
        fixture -> fixture.store().put("max-artifact-bytes", 2_097_152L),
        "max-artifact-bytes must not exceed max-bytes");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-active", 0),
        "configuration.routes[0].budget.max-active must be positive");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-queued", -1),
        "configuration.routes[0].budget.max-queued must be non-negative");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-result-bytes", 0L),
        "configuration.routes[0].budget.max-result-bytes must be positive");
    assertMutation(
        fixture -> fixture.fallback().put("version", -1L),
        "configuration.fallback.version must be non-negative");
  }

  @Test
  void rejectsInvalidDurations() {
    assertMutation(
        fixture -> fixture.renderBudget().put("max-execution-duration", "nope"),
        "ISO-8601 duration");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-execution-duration", "PT0S"),
        "positive duration");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-execution-duration", "-PT1S"),
        "positive duration");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-execution-duration", 10), "must be a string");
  }

  @Test
  void rejectsUnsupportedEnums() {
    assertMutation(
        fixture -> fixture.render().put("strategy", "UNKNOWN"), "unsupported value UNKNOWN");
    assertMutation(
        fixture -> fixture.renderMatch().put("trust", "UNKNOWN"), "unsupported value UNKNOWN");
  }

  @Test
  void rejectsWrongScalarTypesAndBlankStrings() {
    assertMutation(
        fixture -> fixture.render().put("id", "   "),
        "configuration.routes[0].id must not be blank");
    assertMutation(
        fixture -> fixture.store().put("directory", 1),
        "configuration.store.directory must be a string");
    assertMutation(
        fixture -> fixture.store().put("max-bytes", true),
        "configuration.store.max-bytes must be a integer");
    assertMutation(
        fixture -> fixture.render().put("version", 1.5d),
        "configuration.routes[0].version must be a integer");
    assertMutation(
        fixture -> fixture.render().put("strategy", 1),
        "configuration.routes[0].strategy must be a string");
    assertMutation(
        fixture -> fixture.renderMatch().put("path", List.of("bad")),
        "configuration.routes[0].match.path must be a string");
    assertMutation(
        fixture -> fixture.renderKey().put("path", "id"),
        "configuration.routes[0].key.path must be a sequence");
    assertMutation(
        fixture -> fixture.renderQuery().put("order-independent", "yes"),
        "configuration.routes[0].key.query.order-independent must be a boolean");
    assertMutation(
        fixture -> fixture.renderBudget().put("max-active", false),
        "configuration.routes[0].budget.max-active must be a integer");
    assertMutation(
        fixture -> fixture.clientParameters().put("mode", 1),
        "configuration.routes[1].client-computation.parameters.mode must be a string");
  }

  @Test
  void normalizesGatewayScalars() throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();

    var configuration = ConfigurationDecoder.decode(fixture.root());

    assertEquals("8080", configuration.gateway().get("origin.port"));
    assertEquals("false", configuration.gateway().get("forwarded.trust"));
  }

  @Test
  void rejectsInvalidGatewayScalarShape() {
    assertMutation(
        fixture -> fixture.gateway().put("origin.host", ""),
        "configuration.gateway.origin.host must not be blank");
    assertMutation(
        fixture -> fixture.gateway().put("origin.host", List.of("origin.internal")),
        "string, boolean, or integer scalar");
  }

  @Test
  void rejectsDuplicateKeyDimensions() {
    assertMutation(
        fixture -> fixture.renderKey().put("path", List.of("id", "id")), "duplicate value id");
    assertMutation(
        fixture -> fixture.renderQuery().put("include", List.of("variant", "variant")),
        "duplicate value variant");
  }

  @Test
  void suppliesDefaultsForOptionalKeyAndClientStructures() throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.renderKey().remove("path");
    fixture.renderKey().put("query", new java.util.LinkedHashMap<String, Object>());
    fixture.clientComputation().remove("parameters");

    var configuration = ConfigurationDecoder.decode(fixture.root());

    var key = configuration.routes().getFirst().key().orElseThrow();
    assertTrue(key.path().isEmpty());
    var query = key.query().orElseThrow();
    assertTrue(query.include().isEmpty());
    assertFalse(query.orderIndependent());
    assertTrue(
        configuration.routes().get(1).clientComputation().orElseThrow().parameters().isEmpty());
  }

  @Test
  void supportsKeyWithoutQueryAndQueryWithoutOrderFlag() throws ConfigurationException {
    var withoutQuery = ConfigurationTestSupport.objectFixture();
    withoutQuery.renderKey().remove("query");
    var first = ConfigurationDecoder.decode(withoutQuery.root());
    assertTrue(first.routes().getFirst().key().orElseThrow().query().isEmpty());

    var withoutOrder = ConfigurationTestSupport.objectFixture();
    withoutOrder.renderQuery().remove("order-independent");
    var second = ConfigurationDecoder.decode(withoutOrder.root());
    assertFalse(
        second.routes().getFirst().key().orElseThrow().query().orElseThrow().orderIndependent());
  }

  @Test
  void rejectsTooManyRoutesAndDimensions() {
    assertMutation(
        fixture -> {
          List<Object> routes = new ArrayList<>();
          for (int index = 0; index <= ConfigurationLimits.MAX_ROUTES; index++) {
            routes.add(java.util.Map.of());
          }
          fixture.root().put("routes", routes);
        },
        "configuration.routes exceeds maximum entries");

    assertMutation(
        fixture -> {
          List<String> dimensions = new ArrayList<>();
          for (int index = 0; index <= ConfigurationLimits.MAX_DIMENSIONS; index++) {
            dimensions.add("d" + index);
          }
          fixture.renderKey().put("path", dimensions);
        },
        "configuration.routes[0].key.path exceeds maximum entries");
  }

  @Test
  void rejectsTooManyClientParameters() {
    assertMutation(
        fixture -> {
          fixture.clientParameters().clear();
          for (int index = 0; index <= ConfigurationLimits.MAX_PARAMETERS; index++) {
            fixture.clientParameters().put("p" + index, "v");
          }
        },
        "configuration.routes[1].client-computation.parameters exceeds maximum entries");
  }

  private static void assertMutation(
      Consumer<ConfigurationTestSupport.ObjectFixture> mutation, String expected) {
    var fixture = ConfigurationTestSupport.objectFixture();
    mutation.accept(fixture);
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> ConfigurationDecoder.decode(fixture.root()));
    assertTrue(
        exception.getMessage().contains(expected),
        () -> "expected <" + expected + "> in <" + exception.getMessage() + ">");
  }
}
