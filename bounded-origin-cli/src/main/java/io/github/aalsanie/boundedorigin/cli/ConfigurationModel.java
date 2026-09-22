package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.proxy.RepresentationContract;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ConfigurationModel {
  private ConfigurationModel() {}

  enum Strategy {
    ARTIFACT_ONLY,
    BOUNDED_COMPUTE,
    MATERIALIZE,
    CLIENT_COMPUTE,
    DENY
  }

  enum Trust {
    UNTRUSTED,
    TRUSTED
  }

  record RuntimeConfiguration(
      int schema,
      Map<String, String> gateway,
      StoreConfiguration store,
      List<RouteConfiguration> routes,
      FallbackConfiguration fallback) {
    RuntimeConfiguration {
      gateway = Map.copyOf(gateway);
      routes = List.copyOf(routes);
    }
  }

  record StoreConfiguration(String directory, long maxBytes, long maxArtifactBytes) {}

  record RouteConfiguration(
      String id,
      long version,
      int precedence,
      MatchConfiguration match,
      Strategy strategy,
      Optional<KeyConfiguration> key,
      Optional<String> materializerVersion,
      Optional<BudgetConfiguration> budget,
      Optional<ClientComputationConfiguration> clientComputation,
      Optional<RepresentationContract> representation) {}

  record MatchConfiguration(
      Optional<String> method, Optional<String> host, String path, Optional<Trust> trust) {}

  record KeyConfiguration(
      List<String> path, Optional<QueryKeyConfiguration> query, List<String> headers) {
    KeyConfiguration {
      path = List.copyOf(path);
      query = Objects.requireNonNull(query, "query");
      headers = List.copyOf(headers);
    }
  }

  record QueryKeyConfiguration(List<String> include, boolean orderIndependent) {
    QueryKeyConfiguration {
      include = List.copyOf(include);
    }
  }

  record BudgetConfiguration(
      int maxActive, int maxQueued, Duration maxExecutionDuration, long maxResultBytes) {}

  record ClientComputationConfiguration(
      String type, String version, Map<String, String> parameters) {
    ClientComputationConfiguration {
      parameters = Map.copyOf(parameters);
    }
  }

  record FallbackConfiguration(String id, long version, int precedence, Strategy strategy) {}
}
