package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class BenchmarkFixtures {
  static final String EMPTY_BODY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  static final String HOST = "benchmark.test";

  enum Shape {
    FIXED,
    CAPTURE,
    CATCH_ALL
  }

  enum Position {
    FIRST,
    MIDDLE,
    LAST,
    MISS
  }

  enum QueryCase {
    SELECTED,
    NOISE,
    DUPLICATES,
    MISSING,
    BARE,
    EMPTY,
    REVERSED,
    ENCODED
  }

  private BenchmarkFixtures() {}

  static String yaml(int routes, Shape shape, int dimensions, boolean ordered, boolean rawQuery) {
    if (routes < 1 || routes > ConfigurationLimits.MAX_ROUTES) {
      throw new IllegalArgumentException("unsupported route count");
    }
    if (dimensions < 1 || dimensions > ConfigurationLimits.MAX_DIMENSIONS) {
      throw new IllegalArgumentException("unsupported query dimension count");
    }
    StringBuilder yaml =
        new StringBuilder(
            """
            schema: 1
            gateway:
              origin.host: 127.0.0.1
              origin.port: 9000
              temporary.directory: benchmark-spool
            store: {directory: benchmark-store, max-bytes: 1048576, max-artifact-bytes: 65536}
            fallback: {id: fallback, version: 1, precedence: -1, strategy: DENY}
            routes:
            """);
    String selected =
        IntStream.range(0, dimensions).mapToObj(i -> "q" + i).collect(Collectors.joining(", "));
    for (int index = 0; index < routes; index++) {
      String path =
          switch (shape) {
            case FIXED -> "/route/" + index;
            case CAPTURE -> "/route/" + index + "/{id}";
            case CATCH_ALL -> "/route/" + index + "/**";
          };
      yaml.append("  - id: route-").append(index).append('\n');
      yaml.append("    version: 1\n    precedence: ").append(routes - index).append('\n');
      yaml.append("    match: {method: GET, host: benchmark.test, trust: UNTRUSTED, path: '")
          .append(path)
          .append("'}\n");
      yaml.append("    strategy: ARTIFACT_ONLY\n    representation: PUBLIC_IMMUTABLE\n");
      yaml.append("    materializer-version: synthetic-v1\n    key:\n      path: ")
          .append(shape == Shape.CAPTURE ? "[id]" : "[]")
          .append('\n');
      if (!rawQuery) {
        yaml.append("      query: {include: [")
            .append(selected)
            .append("], order-independent: ")
            .append(!ordered)
            .append("}\n");
      }
    }
    return yaml.toString();
  }

  static ConfigurationModel.RuntimeConfiguration load(String yaml)
      throws IOException, ConfigurationException {
    Path path = Files.createTempFile("bounded-origin-benchmark-", ".yaml");
    try {
      Files.writeString(path, yaml, StandardCharsets.UTF_8);
      return new YamlConfigurationLoader().load(path);
    } finally {
      Files.delete(path);
    }
  }

  static String overlappingYaml(int routes) {
    return yaml(routes - 1, Shape.CAPTURE, 1, false, false)
        + """
          - id: overlap-deny
            version: 1
            precedence: 0
            match: {path: '/route/**'}
            strategy: DENY
        """;
  }

  static Budget budget(ConfigurationModel.RuntimeConfiguration configuration) {
    return GatewayConfig.from(configuration.gateway()).globalBudget();
  }

  static PolicyEngine engine(ConfigurationModel.RuntimeConfiguration configuration)
      throws ConfigurationException {
    return PolicyConfigurationCompiler.compile(configuration, budget(configuration));
  }

  static String path(int routes, Shape shape, Position position) {
    int index =
        switch (position) {
          case FIRST -> 0;
          case MIDDLE -> routes / 2;
          case LAST -> routes - 1;
          case MISS -> routes;
        };
    return "/route/"
        + index
        + switch (shape) {
          case FIXED -> "";
          case CAPTURE -> "/item";
          case CATCH_ALL -> "/item/nested";
        };
  }

  static RequestDescriptor request(String path, String query) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("method", List.of("GET"));
    attributes.put("host", List.of(HOST));
    attributes.put("path", List.of(path));
    attributes.put("body-sha256", List.of(EMPTY_BODY));
    if (query != null) {
      attributes.put("query", List.of(query));
    }
    return new RequestDescriptor("http.request", attributes, TrustLevel.UNTRUSTED);
  }

  static String query(int dimensions, QueryCase queryCase) {
    if (queryCase == QueryCase.MISSING) {
      return "";
    }
    String selected =
        IntStream.range(0, dimensions)
            .map(i -> queryCase == QueryCase.REVERSED ? dimensions - i - 1 : i)
            .mapToObj(
                i ->
                    switch (queryCase) {
                      case BARE -> "q" + i;
                      case EMPTY -> "q" + i + "=";
                      case ENCODED -> "%71" + i + "=%76alue";
                      default -> "q" + i + "=value";
                    })
            .collect(Collectors.joining("&"));
    return switch (queryCase) {
      case NOISE -> selected + "&" + "noise=ignored&".repeat(32) + "noise=last";
      case DUPLICATES -> selected + "&" + selected;
      default -> selected;
    };
  }
}
