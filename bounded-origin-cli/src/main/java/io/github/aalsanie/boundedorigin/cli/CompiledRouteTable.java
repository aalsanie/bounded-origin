package io.github.aalsanie.boundedorigin.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class CompiledRouteTable {
  private final List<RouteTemplateCompiler.CompiledRoute> routes;

  CompiledRouteTable(List<RouteTemplateCompiler.CompiledRoute> routes) {
    this.routes = List.copyOf(routes);
  }

  Optional<Match> match(String method, String host, String path, ConfigurationModel.Trust trust) {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(trust, "trust");
    if (!path.startsWith("/")) {
      return Optional.empty();
    }
    List<String> segments = splitPath(path);
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    for (RouteTemplateCompiler.CompiledRoute route : routes) {
      Optional<Map<String, String>> captures = route.match(method, normalizedHost, segments, trust);
      if (captures.isPresent()) {
        return Optional.of(new Match(route.configuration(), captures.orElseThrow()));
      }
    }
    return Optional.empty();
  }

  private static List<String> splitPath(String path) {
    if ("/".equals(path)) {
      return List.of();
    }
    return Arrays.asList(path.substring(1).split("/", -1));
  }

  static final class Match {
    private final ConfigurationModel.RouteConfiguration route;
    private final Map<String, String> pathCaptures;

    Match(ConfigurationModel.RouteConfiguration route, Map<String, String> pathCaptures) {
      this.route = Objects.requireNonNull(route, "route");
      this.pathCaptures = Map.copyOf(pathCaptures);
    }

    ConfigurationModel.RouteConfiguration route() {
      return route;
    }

    Map<String, String> pathCaptures() {
      return pathCaptures;
    }
  }
}
