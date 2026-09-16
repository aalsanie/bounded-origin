package io.github.aalsanie.boundedorigin.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigurationDecoder {
  private static final Set<String> ROOT_KEYS = Set.of("schema", "gateway", "store", "routes", "fallback");
  private static final Set<String> STORE_KEYS =
      Set.of("directory", "max-bytes", "max-artifact-bytes");
  private static final Set<String> FALLBACK_KEYS = Set.of("id", "version", "precedence", "strategy");
  private static final Set<String> GATEWAY_KEYS =
      Set.of(
          "listen.host",
          "listen.port",
          "admin.host",
          "admin.port",
          "origin.host",
          "origin.port",
          "temporary.directory",
          "ingress.trust",
          "forwarded.trust",
          "event-loop.threads",
          "origin.event-loop.threads",
          "frontend.max-connections",
          "server.backlog",
          "http.max-initial-line-bytes",
          "http.max-header-bytes",
          "http.chunk-bytes",
          "http.max-request-body-bytes",
          "http.chunked-response-threshold-bytes",
          "spool.max-bytes",
          "spool.max-files",
          "origin.max-connections",
          "origin.max-pending-acquires",
          "origin.acquire-timeout",
          "origin.max-active",
          "origin.max-queued",
          "origin.max-execution-duration",
          "origin.max-result-bytes",
          "origin.connect-timeout",
          "origin.response-timeout",
          "origin.failure-cooldown",
          "origin.max-cooldown-entries",
          "request.timeout",
          "idle.timeout",
          "drain.timeout",
          "overload.retry-after-seconds",
          "downstream.write-low-watermark-bytes",
          "downstream.write-high-watermark-bytes");

  private ConfigurationDecoder() {}

  static ConfigurationModel.RuntimeConfiguration decode(Object root) throws ConfigurationException {
    Map<String, Object> values = ConfigurationValues.mapping(root, "configuration");
    ConfigurationValues.rejectUnknown(values, ROOT_KEYS, "configuration");
    int schema =
        ConfigurationValues.integerValue(
            ConfigurationValues.required(values, "schema", "configuration"),
            "configuration.schema");
    if (schema != 1) {
      throw new ConfigurationException("configuration.schema must be 1");
    }

    Map<String, String> gateway =
        parseGateway(ConfigurationValues.required(values, "gateway", "configuration"));
    ConfigurationModel.StoreConfiguration store =
        parseStore(ConfigurationValues.required(values, "store", "configuration"));
    List<ConfigurationModel.RouteConfiguration> routes =
        parseRoutes(ConfigurationValues.required(values, "routes", "configuration"));
    ConfigurationModel.FallbackConfiguration fallback =
        parseFallback(ConfigurationValues.required(values, "fallback", "configuration"));
    ensureUniquePolicyIds(routes, fallback);
    return new ConfigurationModel.RuntimeConfiguration(schema, gateway, store, routes, fallback);
  }

  private static Map<String, String> parseGateway(Object value) throws ConfigurationException {
    Map<String, Object> source = ConfigurationValues.mapping(value, "configuration.gateway");
    ConfigurationValues.rejectUnknown(source, GATEWAY_KEYS, "configuration.gateway");
    for (String required : List.of("origin.host", "origin.port", "temporary.directory")) {
      ConfigurationValues.required(source, required, "configuration.gateway");
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      result.put(
          entry.getKey(),
          ConfigurationValues.scalarText(
              entry.getValue(), "configuration.gateway." + entry.getKey()));
    }
    GatewayConfigurationValidator.validate(result);
    return Map.copyOf(result);
  }

  private static ConfigurationModel.StoreConfiguration parseStore(Object value)
      throws ConfigurationException {
    Map<String, Object> source = ConfigurationValues.mapping(value, "configuration.store");
    ConfigurationValues.rejectUnknown(source, STORE_KEYS, "configuration.store");
    String directory =
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, "directory", "configuration.store"),
            "configuration.store.directory");
    long maxBytes =
        ConfigurationValues.positiveLong(
            ConfigurationValues.required(source, "max-bytes", "configuration.store"),
            "configuration.store.max-bytes");
    long maxArtifactBytes =
        ConfigurationValues.nonNegativeLong(
            ConfigurationValues.required(source, "max-artifact-bytes", "configuration.store"),
            "configuration.store.max-artifact-bytes");
    if (maxArtifactBytes > maxBytes) {
      throw new ConfigurationException(
          "configuration.store.max-artifact-bytes must not exceed max-bytes");
    }
    return new ConfigurationModel.StoreConfiguration(directory, maxBytes, maxArtifactBytes);
  }

  private static List<ConfigurationModel.RouteConfiguration> parseRoutes(Object value)
      throws ConfigurationException {
    List<Object> source =
        ConfigurationValues.sequence(
            value, "configuration.routes", ConfigurationLimits.MAX_ROUTES);
    List<ConfigurationModel.RouteConfiguration> routes = new ArrayList<>(source.size());
    for (int index = 0; index < source.size(); index++) {
      routes.add(RouteConfigurationDecoder.decode(source.get(index), index));
    }
    return List.copyOf(routes);
  }

  private static ConfigurationModel.FallbackConfiguration parseFallback(Object value)
      throws ConfigurationException {
    Map<String, Object> source = ConfigurationValues.mapping(value, "configuration.fallback");
    ConfigurationValues.rejectUnknown(source, FALLBACK_KEYS, "configuration.fallback");
    String id =
        ConfigurationValues.nonBlankString(
            ConfigurationValues.required(source, "id", "configuration.fallback"),
            "configuration.fallback.id");
    long version =
        ConfigurationValues.nonNegativeLong(
            ConfigurationValues.required(source, "version", "configuration.fallback"),
            "configuration.fallback.version");
    int precedence =
        ConfigurationValues.integerValue(
            ConfigurationValues.required(source, "precedence", "configuration.fallback"),
            "configuration.fallback.precedence");
    ConfigurationModel.Strategy strategy =
        ConfigurationValues.enumValue(
            ConfigurationValues.required(source, "strategy", "configuration.fallback"),
            "configuration.fallback.strategy",
            ConfigurationModel.Strategy.class);
    if (strategy != ConfigurationModel.Strategy.DENY) {
      throw new ConfigurationException("configuration.fallback.strategy must be DENY");
    }
    return new ConfigurationModel.FallbackConfiguration(id, version, precedence, strategy);
  }

  private static void ensureUniquePolicyIds(
      List<ConfigurationModel.RouteConfiguration> routes,
      ConfigurationModel.FallbackConfiguration fallback)
      throws ConfigurationException {
    Set<String> ids = new HashSet<>();
    ids.add(fallback.id());
    for (ConfigurationModel.RouteConfiguration route : routes) {
      if (!ids.add(route.id())) {
        throw new ConfigurationException("duplicate policy id " + route.id());
      }
    }
  }
}
