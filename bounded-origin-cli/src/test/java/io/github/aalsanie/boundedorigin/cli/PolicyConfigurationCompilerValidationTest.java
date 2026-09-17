package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyConfigurationCompilerValidationTest {
  private static final Budget GLOBAL_BUDGET =
      new Budget(8, 64, Duration.ofSeconds(20), 1_048_576);

  @Test
  void rejectsNullBudgetDurationAsConfigurationError() {
    ConfigurationModel.RouteConfiguration route =
        boundedRoute(new ConfigurationModel.BudgetConfiguration(1, 1, null, 1));

    assertConfigurationFailure(route, ".budget.max-execution-duration must not be null");
  }

  @Test
  void rejectsInvalidClientComputationBeforeApiConstruction() {
    assertConfigurationFailure(
        clientRoute(new ConfigurationModel.ClientComputationConfiguration(null, "v1", Map.of())),
        ".client-computation.type must not be blank");
    assertConfigurationFailure(
        clientRoute(new ConfigurationModel.ClientComputationConfiguration("wasm", null, Map.of())),
        ".client-computation.version must not be blank");
    assertConfigurationFailure(
        clientRoute(
            new ConfigurationModel.ClientComputationConfiguration(
                "wasm", "v1", Map.of("", "value"))),
        ".client-computation is invalid");
  }

  private static void assertConfigurationFailure(
      ConfigurationModel.RouteConfiguration route, String message) {
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> PolicyConfigurationCompiler.compile(runtime(route), GLOBAL_BUDGET));

    assertTrue(exception.getMessage().contains(message));
  }

  private static ConfigurationModel.RuntimeConfiguration runtime(
      ConfigurationModel.RouteConfiguration route) {
    return new ConfigurationModel.RuntimeConfiguration(
        1,
        Map.of(),
        new ConfigurationModel.StoreConfiguration("store", 1, 1),
        List.of(route),
        new ConfigurationModel.FallbackConfiguration(
            "default-deny", 1, Integer.MIN_VALUE, ConfigurationModel.Strategy.DENY));
  }

  private static ConfigurationModel.RouteConfiguration boundedRoute(
      ConfigurationModel.BudgetConfiguration budget) {
    return new ConfigurationModel.RouteConfiguration(
        "bounded",
        1,
        100,
        match("/bounded/{id}"),
        ConfigurationModel.Strategy.BOUNDED_COMPUTE,
        key(),
        Optional.of("v1"),
        Optional.of(budget),
        Optional.empty());
  }

  private static ConfigurationModel.RouteConfiguration clientRoute(
      ConfigurationModel.ClientComputationConfiguration clientComputation) {
    return new ConfigurationModel.RouteConfiguration(
        "client",
        1,
        100,
        match("/client/{id}"),
        ConfigurationModel.Strategy.CLIENT_COMPUTE,
        key(),
        Optional.of("v1"),
        Optional.empty(),
        Optional.of(clientComputation));
  }

  private static ConfigurationModel.MatchConfiguration match(String path) {
    return new ConfigurationModel.MatchConfiguration(
        Optional.of("GET"),
        Optional.of("example.com"),
        path,
        Optional.of(ConfigurationModel.Trust.UNTRUSTED));
  }

  private static Optional<ConfigurationModel.KeyConfiguration> key() {
    return Optional.of(new ConfigurationModel.KeyConfiguration(List.of("id"), Optional.empty()));
  }
}
