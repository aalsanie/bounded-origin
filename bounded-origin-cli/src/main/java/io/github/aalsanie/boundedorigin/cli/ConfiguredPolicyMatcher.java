package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.PolicyMatcher;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ConfiguredPolicyMatcher implements PolicyMatcher {
  private static final String REQUEST_TYPE = "http.request";

  private final CompiledRouteTable routeTable;
  private final Optional<SemanticKeyPlan> semanticKeyPlan;

  private ConfiguredPolicyMatcher(
      CompiledRouteTable routeTable, Optional<SemanticKeyPlan> semanticKeyPlan) {
    this.routeTable = Objects.requireNonNull(routeTable, "routeTable");
    this.semanticKeyPlan = Objects.requireNonNull(semanticKeyPlan, "semanticKeyPlan");
  }

  static ConfiguredPolicyMatcher compile(
      ConfigurationModel.RouteConfiguration route, String path, boolean keyed)
      throws ConfigurationException {
    RouteTemplateCompiler.CompiledRoute compiled =
        RouteTemplateCompiler.CompiledRoute.compile(route, path);
    Optional<SemanticKeyPlan> keyPlan = Optional.empty();
    if (keyed) {
      CompiledPathTemplate template =
          CompiledPathTemplate.compile(route.match().path(), path + ".match.path");
      keyPlan = Optional.of(SemanticKeyPlan.compile(route, template.captureNames(), path));
    }
    return new ConfiguredPolicyMatcher(new CompiledRouteTable(List.of(compiled)), keyPlan);
  }

  Optional<io.github.aalsanie.boundedorigin.api.Canonicalizer> canonicalizer() {
    return semanticKeyPlan.map(SemanticKeyPlan::canonicalizer);
  }

  @Override
  public Optional<Operation> classify(RequestDescriptor request) {
    Objects.requireNonNull(request, "request");
    if (!REQUEST_TYPE.equals(request.name())) {
      return Optional.empty();
    }
    Optional<CompiledRouteTable.Match> match =
        routeTable.match(
            singleAttribute(request, "method"),
            singleAttribute(request, "host"),
            singleAttribute(request, "path"),
            trust(request.trustLevel()));
    if (match.isEmpty()) {
      return Optional.empty();
    }
    if (semanticKeyPlan.isEmpty()) {
      return Optional.of(new Operation(REQUEST_TYPE, Map.of()));
    }
    return Optional.of(
        semanticKeyPlan.orElseThrow().operation(request, match.orElseThrow().pathCaptures()));
  }

  private static String singleAttribute(RequestDescriptor request, String name) {
    List<String> values = request.attributes().get(name);
    if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
      throw new IllegalArgumentException(name + " must contain exactly one non-blank value");
    }
    return values.getFirst();
  }

  private static ConfigurationModel.Trust trust(TrustLevel trustLevel) {
    return switch (trustLevel) {
      case UNTRUSTED -> ConfigurationModel.Trust.UNTRUSTED;
      case TRUSTED -> ConfigurationModel.Trust.TRUSTED;
    };
  }
}
