package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PolicyConfigurationCompiler {
  private static final Comparator<PolicyRule> RULE_ORDER =
      Comparator.comparingInt((PolicyRule rule) -> rule.policy().precedence())
          .reversed()
          .thenComparing(rule -> rule.policy().id());

  private PolicyConfigurationCompiler() {}

  static PolicyEngine compile(
      ConfigurationModel.RuntimeConfiguration configuration, Budget globalBudget)
      throws ConfigurationException {
    if (configuration == null) {
      throw new ConfigurationException("configuration must not be null");
    }
    if (globalBudget == null) {
      throw new ConfigurationException("global budget must not be null");
    }

    RouteTemplateCompiler.compile(configuration.routes());
    ConfigurationModel.FallbackConfiguration fallback = configuration.fallback();
    validateFallback(fallback, configuration.routes());

    List<PolicyRule> rules = new ArrayList<>(configuration.routes().size());
    for (int index = 0; index < configuration.routes().size(); index++) {
      ConfigurationModel.RouteConfiguration route = configuration.routes().get(index);
      rules.add(compileRoute(route, index, globalBudget));
    }
    rules.sort(RULE_ORDER);

    OriginPolicy fallbackPolicy =
        OriginPolicy.deny(fallback.id(), fallback.version(), fallback.precedence());
    try {
      return new PolicyEngine(rules, fallbackPolicy);
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException("configuration policies are invalid", exception);
    }
  }

  private static PolicyRule compileRoute(
      ConfigurationModel.RouteConfiguration route, int index, Budget globalBudget)
      throws ConfigurationException {
    String path = "configuration.routes[" + index + "]";
    validateRouteShape(route, path);
    validateStrategyFields(route, path);

    boolean keyed = route.strategy() != ConfigurationModel.Strategy.DENY;
    ConfiguredPolicyMatcher matcher = ConfiguredPolicyMatcher.compile(route, path, keyed);
    OriginPolicy policy;
    try {
      policy = createPolicy(route, matcher, globalBudget, path);
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException(path + " cannot be compiled into a policy", exception);
    }
    return new PolicyRule(policy, matcher);
  }

  private static OriginPolicy createPolicy(
      ConfigurationModel.RouteConfiguration route,
      ConfiguredPolicyMatcher matcher,
      Budget globalBudget,
      String path)
      throws ConfigurationException {
    return switch (route.strategy()) {
      case ARTIFACT_ONLY ->
          OriginPolicy.artifactOnly(
              route.id(),
              route.version(),
              route.precedence(),
              route.materializerVersion().orElseThrow(),
              requiredCanonicalizer(matcher, path));
      case BOUNDED_COMPUTE ->
          OriginPolicy.boundedCompute(
              route.id(),
              route.version(),
              route.precedence(),
              route.materializerVersion().orElseThrow(),
              requiredCanonicalizer(matcher, path),
              policyBudget(route.budget().orElseThrow(), globalBudget, path));
      case MATERIALIZE ->
          OriginPolicy.materialize(
              route.id(),
              route.version(),
              route.precedence(),
              route.materializerVersion().orElseThrow(),
              requiredCanonicalizer(matcher, path),
              policyBudget(route.budget().orElseThrow(), globalBudget, path));
      case CLIENT_COMPUTE ->
          OriginPolicy.clientCompute(
              route.id(),
              route.version(),
              route.precedence(),
              route.materializerVersion().orElseThrow(),
              requiredCanonicalizer(matcher, path),
              clientComputation(route.clientComputation().orElseThrow(), path));
      case DENY -> OriginPolicy.deny(route.id(), route.version(), route.precedence());
    };
  }

  private static void validateFallback(
      ConfigurationModel.FallbackConfiguration fallback,
      List<ConfigurationModel.RouteConfiguration> routes)
      throws ConfigurationException {
    if (fallback == null) {
      throw new ConfigurationException("configuration.fallback must not be null");
    }
    if (fallback.id() == null || fallback.id().isBlank()) {
      throw new ConfigurationException("configuration.fallback.id must not be blank");
    }
    if (fallback.version() < 0) {
      throw new ConfigurationException("configuration.fallback.version must be non-negative");
    }
    if (fallback.strategy() != ConfigurationModel.Strategy.DENY) {
      throw new ConfigurationException("configuration.fallback.strategy must be DENY");
    }

    Set<String> routeIds = new HashSet<>();
    for (int index = 0; index < routes.size(); index++) {
      ConfigurationModel.RouteConfiguration route = routes.get(index);
      if (route == null) {
        throw new ConfigurationException("configuration.routes[" + index + "] must not be null");
      }
      if (route.id() != null) {
        routeIds.add(route.id());
      }
      if (route.precedence() <= fallback.precedence()) {
        throw new ConfigurationException(
            "configuration.routes["
                + index
                + "].precedence must exceed configuration.fallback.precedence");
      }
    }
    if (routeIds.contains(fallback.id())) {
      throw new ConfigurationException("duplicate policy id " + fallback.id());
    }
  }

  private static void validateRouteShape(ConfigurationModel.RouteConfiguration route, String path)
      throws ConfigurationException {
    Objects.requireNonNull(route, "route");
    if (route.id() == null || route.id().isBlank()) {
      throw new ConfigurationException(path + ".id must not be blank");
    }
    if (route.version() < 0) {
      throw new ConfigurationException(path + ".version must be non-negative");
    }
    if (route.strategy() == null) {
      throw new ConfigurationException(path + ".strategy must not be null");
    }
    if (route.key() == null
        || route.materializerVersion() == null
        || route.budget() == null
        || route.clientComputation() == null) {
      throw new ConfigurationException(path + " optional policy fields must not be null");
    }
  }

  private static void validateStrategyFields(
      ConfigurationModel.RouteConfiguration route, String path) throws ConfigurationException {
    switch (route.strategy()) {
      case ARTIFACT_ONLY -> {
        requireKeyed(route, path);
        requireAbsent(route.budget(), path + ".budget", route.strategy());
        requireAbsent(route.clientComputation(), path + ".client-computation", route.strategy());
      }
      case BOUNDED_COMPUTE, MATERIALIZE -> {
        requireKeyed(route, path);
        requirePresent(route.budget(), path + ".budget", route.strategy());
        requireAbsent(route.clientComputation(), path + ".client-computation", route.strategy());
      }
      case CLIENT_COMPUTE -> {
        requireKeyed(route, path);
        requireAbsent(route.budget(), path + ".budget", route.strategy());
        requirePresent(route.clientComputation(), path + ".client-computation", route.strategy());
      }
      case DENY -> {
        requireAbsent(route.key(), path + ".key", route.strategy());
        requireAbsent(
            route.materializerVersion(), path + ".materializer-version", route.strategy());
        requireAbsent(route.budget(), path + ".budget", route.strategy());
        requireAbsent(route.clientComputation(), path + ".client-computation", route.strategy());
      }
    }
  }

  private static void requireKeyed(ConfigurationModel.RouteConfiguration route, String path)
      throws ConfigurationException {
    requirePresent(route.key(), path + ".key", route.strategy());
    requirePresent(route.materializerVersion(), path + ".materializer-version", route.strategy());
    String materializerVersion = route.materializerVersion().orElseThrow();
    if (materializerVersion.isBlank()) {
      throw new ConfigurationException(path + ".materializer-version must not be blank");
    }
  }

  private static void requirePresent(
      Optional<?> value, String path, ConfigurationModel.Strategy strategy)
      throws ConfigurationException {
    if (value.isEmpty()) {
      throw new ConfigurationException(path + " is required for " + strategy);
    }
  }

  private static void requireAbsent(
      Optional<?> value, String path, ConfigurationModel.Strategy strategy)
      throws ConfigurationException {
    if (value.isPresent()) {
      throw new ConfigurationException(path + " is not allowed for " + strategy);
    }
  }

  private static Canonicalizer requiredCanonicalizer(ConfiguredPolicyMatcher matcher, String path)
      throws ConfigurationException {
    return matcher
        .canonicalizer()
        .orElseThrow(
            () -> new ConfigurationException(path + ".key did not compile a canonicalizer"));
  }

  private static Budget policyBudget(
      ConfigurationModel.BudgetConfiguration configured, Budget global, String path)
      throws ConfigurationException {
    if (configured.maxExecutionDuration() == null) {
      throw new ConfigurationException(path + ".budget.max-execution-duration must not be null");
    }

    Budget budget;
    try {
      budget =
          new Budget(
              configured.maxActive(),
              configured.maxQueued(),
              configured.maxExecutionDuration(),
              configured.maxResultBytes());
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException(path + ".budget is invalid", exception);
    }
    if (budget.maxActive() > global.maxActive()) {
      throw exceedsGlobal(path, "max-active");
    }
    if (budget.maxQueued() > global.maxQueued()) {
      throw exceedsGlobal(path, "max-queued");
    }
    if (budget.timeout().compareTo(global.timeout()) > 0) {
      throw exceedsGlobal(path, "max-execution-duration");
    }
    if (budget.maxResultBytes() > global.maxResultBytes()) {
      throw exceedsGlobal(path, "max-result-bytes");
    }
    return budget;
  }

  private static ConfigurationException exceedsGlobal(String path, String field) {
    return new ConfigurationException(path + ".budget." + field + " exceeds global origin budget");
  }

  private static ClientComputation clientComputation(
      ConfigurationModel.ClientComputationConfiguration configured, String path)
      throws ConfigurationException {
    if (configured.type() == null || configured.type().isBlank()) {
      throw new ConfigurationException(path + ".client-computation.type must not be blank");
    }
    if (configured.version() == null || configured.version().isBlank()) {
      throw new ConfigurationException(path + ".client-computation.version must not be blank");
    }
    try {
      return new ClientComputation(
          configured.type(), configured.version(), configured.parameters());
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException(path + ".client-computation is invalid", exception);
    }
  }
}
