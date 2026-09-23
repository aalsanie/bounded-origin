package io.github.aalsanie.boundedorigin.cli;

import java.util.Optional;

final class RouteTemplateTestSupport {
  private RouteTemplateTestSupport() {}

  static ConfigurationModel.RouteConfiguration route(String id, int precedence, String path) {
    return route(id, precedence, path, null, null, null);
  }

  static ConfigurationModel.RouteConfiguration route(
      String id,
      int precedence,
      String path,
      String method,
      String host,
      ConfigurationModel.Trust trust) {
    return new ConfigurationModel.RouteConfiguration(
        id,
        1,
        precedence,
        new ConfigurationModel.MatchConfiguration(
            Optional.ofNullable(method),
            Optional.ofNullable(host),
            path,
            Optional.ofNullable(trust)),
        ConfigurationModel.Strategy.DENY,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  static ConfigurationModel.RouteConfiguration routeWithMatch(
      String id, int precedence, ConfigurationModel.MatchConfiguration match) {
    return new ConfigurationModel.RouteConfiguration(
        id,
        1,
        precedence,
        match,
        ConfigurationModel.Strategy.DENY,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
