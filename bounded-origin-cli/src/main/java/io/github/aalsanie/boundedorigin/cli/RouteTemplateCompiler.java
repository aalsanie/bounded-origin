package io.github.aalsanie.boundedorigin.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class RouteTemplateCompiler {
  private static final String HTTP_TOKEN_CHARACTERS =
      "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final Comparator<CompiledRoute> ROUTE_ORDER =
      Comparator.comparingInt((CompiledRoute route) -> route.configuration().precedence())
          .reversed()
          .thenComparing(route -> route.configuration().id());

  private RouteTemplateCompiler() {}

  static CompiledRouteTable compile(List<ConfigurationModel.RouteConfiguration> routes)
      throws ConfigurationException {
    if (routes == null) {
      throw new ConfigurationException("configuration.routes must not be null");
    }
    if (routes.size() > ConfigurationLimits.MAX_ROUTES) {
      throw new ConfigurationException(
          "configuration.routes exceeds maximum size " + ConfigurationLimits.MAX_ROUTES);
    }

    List<CompiledRoute> compiled = new ArrayList<>(routes.size());
    for (int index = 0; index < routes.size(); index++) {
      ConfigurationModel.RouteConfiguration route = routes.get(index);
      if (route == null) {
        throw new ConfigurationException("configuration.routes[" + index + "] must not be null");
      }
      compiled.add(CompiledRoute.compile(route, "configuration.routes[" + index + "]"));
    }
    compiled.sort(ROUTE_ORDER);
    rejectDuplicateIds(compiled);
    rejectAmbiguousSamePrecedence(compiled);
    return new CompiledRouteTable(compiled);
  }

  private static void rejectDuplicateIds(List<CompiledRoute> routes) throws ConfigurationException {
    Set<String> ids = new HashSet<>();
    for (CompiledRoute route : routes) {
      String id = route.configuration().id();
      if (!ids.add(id)) {
        throw new ConfigurationException("configuration.routes contains duplicate id '" + id + "'");
      }
    }
  }

  private static void rejectAmbiguousSamePrecedence(List<CompiledRoute> routes)
      throws ConfigurationException {
    for (int left = 0; left < routes.size(); left++) {
      CompiledRoute first = routes.get(left);
      for (int right = left + 1; right < routes.size(); right++) {
        CompiledRoute second = routes.get(right);
        if (first.configuration().precedence() != second.configuration().precedence()) {
          break;
        }
        if (first.overlaps(second)) {
          throw new ConfigurationException(
              "configuration.routes contains ambiguous same-precedence overlap between '"
                  + first.configuration().id()
                  + "' and '"
                  + second.configuration().id()
                  + "'");
        }
      }
    }
  }

  static final class CompiledRoute {
    private final ConfigurationModel.RouteConfiguration configuration;
    private final String method;
    private final String host;
    private final ConfigurationModel.Trust trust;
    private final CompiledPathTemplate pathTemplate;

    private CompiledRoute(
        ConfigurationModel.RouteConfiguration configuration,
        String method,
        String host,
        ConfigurationModel.Trust trust,
        CompiledPathTemplate pathTemplate) {
      this.configuration = configuration;
      this.method = method;
      this.host = host;
      this.trust = trust;
      this.pathTemplate = pathTemplate;
    }

    static CompiledRoute compile(ConfigurationModel.RouteConfiguration route, String path)
        throws ConfigurationException {
      if (route.id() == null || route.id().isBlank()) {
        throw new ConfigurationException(path + ".id must not be blank");
      }
      ConfigurationModel.MatchConfiguration match = route.match();
      if (match == null) {
        throw new ConfigurationException(path + ".match must not be null");
      }
      String method = compileMethod(match.method(), path + ".match.method");
      String host = compileHost(match.host(), path + ".match.host");
      if (match.trust() == null) {
        throw new ConfigurationException(path + ".match.trust must not be null");
      }
      CompiledPathTemplate template =
          CompiledPathTemplate.compile(match.path(), path + ".match.path");
      return new CompiledRoute(route, method, host, match.trust().orElse(null), template);
    }

    ConfigurationModel.RouteConfiguration configuration() {
      return configuration;
    }

    Optional<Map<String, String>> match(
        String requestMethod,
        String requestHost,
        List<String> requestSegments,
        ConfigurationModel.Trust requestTrust) {
      if (method != null && !method.equals(requestMethod)) {
        return Optional.empty();
      }
      if (host != null && !host.equals(requestHost)) {
        return Optional.empty();
      }
      if (trust != null && trust != requestTrust) {
        return Optional.empty();
      }
      return pathTemplate.match(requestSegments);
    }

    boolean overlaps(CompiledRoute other) {
      return constraintsOverlap(method, other.method)
          && constraintsOverlap(host, other.host)
          && (trust == null || other.trust == null || trust == other.trust)
          && pathTemplate.overlaps(other.pathTemplate);
    }

    private static boolean constraintsOverlap(String left, String right) {
      return left == null || right == null || left.equals(right);
    }
  }

  private static String compileMethod(Optional<String> method, String path)
      throws ConfigurationException {
    if (method == null) {
      throw new ConfigurationException(path + " must not be null");
    }
    if (method.isEmpty()) {
      return null;
    }
    String value = method.orElseThrow();
    if (value.isBlank()) {
      throw new ConfigurationException(path + " must not be blank");
    }
    for (int index = 0; index < value.length(); index++) {
      if (!isTokenCharacter(value.charAt(index))) {
        throw new ConfigurationException(path + " contains an invalid HTTP method character");
      }
    }
    return value;
  }

  private static String compileHost(Optional<String> host, String path)
      throws ConfigurationException {
    if (host == null) {
      throw new ConfigurationException(path + " must not be null");
    }
    if (host.isEmpty()) {
      return null;
    }
    String value = host.orElseThrow();
    if (value.isBlank()) {
      throw new ConfigurationException(path + " must not be blank");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21
          || character > 0x7e
          || character == '/'
          || character == '\\'
          || character == '?'
          || character == '#'
          || character == '@'
          || character == ','
          || character == '*') {
        throw new ConfigurationException(path + " contains an invalid host constraint");
      }
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static boolean isTokenCharacter(char character) {
    return HTTP_TOKEN_CHARACTERS.indexOf(character) >= 0;
  }
}
