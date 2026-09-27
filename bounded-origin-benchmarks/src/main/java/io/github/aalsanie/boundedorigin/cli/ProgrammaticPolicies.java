package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import io.github.aalsanie.boundedorigin.proxy.HttpOperation;
import io.github.aalsanie.boundedorigin.proxy.RepresentationContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ProgrammaticPolicies {
  private ProgrammaticPolicies() {}

  static PolicyEngine fixedPaths(int routeCount) {
    List<PolicyRule> rules = new ArrayList<>();
    for (int index = 0; index < routeCount; index++) {
      String path = "/route/" + index;
      OriginPolicy policy =
          OriginPolicy.artifactOnly(
              "route-" + index,
              1,
              routeCount - index,
              "synthetic-v1",
              HttpOperation.canonicalizer());
      rules.add(new PolicyRule(policy, request -> classify(request, path)));
    }
    return new PolicyEngine(rules, OriginPolicy.deny("fallback", 1, -1));
  }

  private static Optional<Operation> classify(RequestDescriptor request, String path) {
    Map<String, List<String>> attributes = request.attributes();
    if (!"http.request".equals(request.name())
        || !List.of("GET").equals(attributes.get("method"))
        || !List.of(BenchmarkFixtures.HOST).equals(attributes.get("host"))
        || request.trustLevel() != TrustLevel.UNTRUSTED
        || !List.of(path).equals(attributes.get("path"))) {
      return Optional.empty();
    }
    String query = attributes.getOrDefault("query", List.of("")).getFirst();
    return Optional.of(
        new HttpOperation(
                "GET",
                BenchmarkFixtures.HOST,
                path + (query.isEmpty() ? "" : "?" + query),
                attributes.get("body-sha256").getFirst(),
                TrustLevel.UNTRUSTED,
                RepresentationContract.PUBLIC_IMMUTABLE,
                Map.of())
            .operation());
  }
}
