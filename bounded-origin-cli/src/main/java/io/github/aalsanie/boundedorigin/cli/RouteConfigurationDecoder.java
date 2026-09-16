package io.github.aalsanie.boundedorigin.cli;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class RouteConfigurationDecoder {
  private static final Set<String> ROUTE_KEYS =
      Set.of(
          "id",
          "version",
          "precedence",
          "match",
          "strategy",
          "key",
          "materializer-version",
          "budget",
          "client-computation");
  private static final Set<String> MATCH_KEYS = Set.of("method", "host", "path", "trust");
  private static final Set<String> KEY_KEYS = Set.of("path", "query");
  private static final Set<String> QUERY_KEYS = Set.of("include", "order-independent");
  private static final Set<String> BUDGET_KEYS =
      Set.of("max-active", "max-queued", "max-execution-duration", "max-result-bytes");
  private static final Set<String> CLIENT_KEYS = Set.of("type", "version", "parameters");

  private RouteConfigurationDecoder() {}

  static ConfigurationModel.RouteConfiguration decode(Object value, int index)
      throws ConfigurationException {
    String path = "configuration.routes[" + index + "]";
    Map<String, Object> source = ConfigurationValues.mapping(value, path);
    ConfigurationValues.rejectUnknown(source, ROUTE_KEYS, path);
    String id =
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, "id", path), path + ".id");
    long version =
        ConfigurationValues.nonNegativeLong(
            ConfigurationValues.required(source, "version", path), path + ".version");
    int precedence =
        ConfigurationValues.integerValue(
            ConfigurationValues.required(source, "precedence", path), path + ".precedence");
    ConfigurationModel.MatchConfiguration match =
        parseMatch(ConfigurationValues.required(source, "match", path), path + ".match");
    ConfigurationModel.Strategy strategy =
        ConfigurationValues.enumValue(
            ConfigurationValues.required(source, "strategy", path),
            path + ".strategy",
            ConfigurationModel.Strategy.class);
    Optional<ConfigurationModel.KeyConfiguration> key = optionalKey(source, path);
    Optional<String> materializerVersion = optionalString(source, "materializer-version", path);
    Optional<ConfigurationModel.BudgetConfiguration> budget = optionalBudget(source, path);
    Optional<ConfigurationModel.ClientComputationConfiguration> client = optionalClient(source, path);
    return new ConfigurationModel.RouteConfiguration(
        id, version, precedence, match, strategy, key, materializerVersion, budget, client);
  }

  private static ConfigurationModel.MatchConfiguration parseMatch(Object value, String path)
      throws ConfigurationException {
    Map<String, Object> source = ConfigurationValues.mapping(value, path);
    ConfigurationValues.rejectUnknown(source, MATCH_KEYS, path);
    String requestPath =
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, "path", path), path + ".path");
    Optional<String> method = optionalString(source, "method", path);
    Optional<String> host = optionalString(source, "host", path);
    Optional<ConfigurationModel.Trust> trust = Optional.empty();
    if (source.containsKey("trust")) {
      trust =
          Optional.of(
              ConfigurationValues.enumValue(
                  ConfigurationValues.required(source, "trust", path),
                  path + ".trust",
                  ConfigurationModel.Trust.class));
    }
    return new ConfigurationModel.MatchConfiguration(method, host, requestPath, trust);
  }

  private static Optional<ConfigurationModel.KeyConfiguration> optionalKey(
      Map<String, Object> route, String path) throws ConfigurationException {
    if (!route.containsKey("key")) {
      return Optional.empty();
    }
    String keyPath = path + ".key";
    Map<String, Object> source =
        ConfigurationValues.mapping(
            ConfigurationValues.required(route, "key", path), keyPath);
    ConfigurationValues.rejectUnknown(source, KEY_KEYS, keyPath);
    var pathDimensions =
        source.containsKey("path")
            ? ConfigurationValues.uniqueStringList(
                ConfigurationValues.required(source, "path", keyPath),
                keyPath + ".path",
                ConfigurationLimits.MAX_DIMENSIONS)
            : java.util.List.<String>of();
    ConfigurationModel.QueryKeyConfiguration query =
        source.containsKey("query")
            ? parseQuery(
                ConfigurationValues.required(source, "query", keyPath), keyPath + ".query")
            : new ConfigurationModel.QueryKeyConfiguration(java.util.List.of(), false);
    return Optional.of(new ConfigurationModel.KeyConfiguration(pathDimensions, query));
  }

  private static ConfigurationModel.QueryKeyConfiguration parseQuery(Object value, String path)
      throws ConfigurationException {
    Map<String, Object> source = ConfigurationValues.mapping(value, path);
    ConfigurationValues.rejectUnknown(source, QUERY_KEYS, path);
    var include =
        source.containsKey("include")
            ? ConfigurationValues.uniqueStringList(
                ConfigurationValues.required(source, "include", path),
                path + ".include",
                ConfigurationLimits.MAX_DIMENSIONS)
            : java.util.List.<String>of();
    boolean orderIndependent =
        source.containsKey("order-independent")
            && ConfigurationValues.booleanValue(
                ConfigurationValues.required(source, "order-independent", path),
                path + ".order-independent");
    return new ConfigurationModel.QueryKeyConfiguration(include, orderIndependent);
  }

  private static Optional<ConfigurationModel.BudgetConfiguration> optionalBudget(
      Map<String, Object> route, String path) throws ConfigurationException {
    if (!route.containsKey("budget")) {
      return Optional.empty();
    }
    String budgetPath = path + ".budget";
    Map<String, Object> source =
        ConfigurationValues.mapping(
            ConfigurationValues.required(route, "budget", path), budgetPath);
    ConfigurationValues.rejectUnknown(source, BUDGET_KEYS, budgetPath);
    int maxActive =
        ConfigurationValues.positiveInt(
            ConfigurationValues.required(source, "max-active", budgetPath),
            budgetPath + ".max-active");
    int maxQueued =
        ConfigurationValues.nonNegativeInt(
            ConfigurationValues.required(source, "max-queued", budgetPath),
            budgetPath + ".max-queued");
    var duration =
        ConfigurationValues.positiveDuration(
            ConfigurationValues.required(source, "max-execution-duration", budgetPath),
            budgetPath + ".max-execution-duration");
    long maxResultBytes =
        ConfigurationValues.positiveLong(
            ConfigurationValues.required(source, "max-result-bytes", budgetPath),
            budgetPath + ".max-result-bytes");
    return Optional.of(
        new ConfigurationModel.BudgetConfiguration(
            maxActive, maxQueued, duration, maxResultBytes));
  }

  private static Optional<ConfigurationModel.ClientComputationConfiguration> optionalClient(
      Map<String, Object> route, String path) throws ConfigurationException {
    if (!route.containsKey("client-computation")) {
      return Optional.empty();
    }
    String clientPath = path + ".client-computation";
    Map<String, Object> source =
        ConfigurationValues.mapping(
            ConfigurationValues.required(route, "client-computation", path), clientPath);
    ConfigurationValues.rejectUnknown(source, CLIENT_KEYS, clientPath);
    String type =
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, "type", clientPath), clientPath + ".type");
    String version =
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, "version", clientPath), clientPath + ".version");
    Map<String, String> parameters =
        source.containsKey("parameters")
            ? ConfigurationValues.stringMap(
                ConfigurationValues.required(source, "parameters", clientPath),
                clientPath + ".parameters",
                ConfigurationLimits.MAX_PARAMETERS)
            : Map.of();
    return Optional.of(
        new ConfigurationModel.ClientComputationConfiguration(type, version, parameters));
  }

  private static Optional<String> optionalString(
      Map<String, Object> source, String key, String path) throws ConfigurationException {
    if (!source.containsKey(key)) {
      return Optional.empty();
    }
    return Optional.of(
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, key, path), path + "." + key));
  }
}
