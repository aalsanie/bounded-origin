package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class SemanticKeyPlan {
  private static final String METHOD = "method";
  private static final String HOST = "host";
  private static final String TRUST = "trust";
  private static final String PATH = "path";
  private static final String QUERY = "query";
  private static final String BODY_SHA256 = "body-sha256";

  private final List<String> pathCaptures;
  private final Set<String> selectedQueryNames;
  private final boolean rawQuery;
  private final boolean orderIndependentQuery;
  private final boolean includeMethod;
  private final boolean includeHost;
  private final boolean includeTrust;
  private final boolean includeFullPath;
  private final boolean includeQuery;
  private final Canonicalizer canonicalizer;

  private SemanticKeyPlan(
      List<String> pathCaptures,
      Set<String> selectedQueryNames,
      boolean rawQuery,
      boolean orderIndependentQuery,
      boolean includeMethod,
      boolean includeHost,
      boolean includeTrust,
      boolean includeFullPath,
      boolean includeQuery,
      Canonicalizer canonicalizer) {
    this.pathCaptures = List.copyOf(pathCaptures);
    this.selectedQueryNames = Set.copyOf(selectedQueryNames);
    this.rawQuery = rawQuery;
    this.orderIndependentQuery = orderIndependentQuery;
    this.includeMethod = includeMethod;
    this.includeHost = includeHost;
    this.includeTrust = includeTrust;
    this.includeFullPath = includeFullPath;
    this.includeQuery = includeQuery;
    this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
  }

  static SemanticKeyPlan compile(
      ConfigurationModel.RouteConfiguration route, Set<String> captureNames, String path)
      throws ConfigurationException {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(captureNames, "captureNames");
    Objects.requireNonNull(path, "path");
    if (route.key() == null || route.key().isEmpty()) {
      throw new ConfigurationException(path + ".key is required for keyed strategy");
    }
    ConfigurationModel.KeyConfiguration key = route.key().orElseThrow();
    if (key.path() == null) {
      throw new ConfigurationException(path + ".key.path must not be null");
    }

    Set<String> configuredPath = new HashSet<>(key.path());
    if (configuredPath.size() != key.path().size()) {
      throw new ConfigurationException(path + ".key.path contains duplicate capture names");
    }
    for (String capture : key.path()) {
      if (!captureNames.contains(capture)) {
        throw new ConfigurationException(
            path + ".key.path references unknown capture '" + capture + "'");
      }
    }
    for (String capture : captureNames) {
      if (!configuredPath.contains(capture)) {
        throw new ConfigurationException(
            path + ".key.path must include variable capture '" + capture + "'");
      }
    }

    List<String> orderedCaptures = key.path().stream().sorted().toList();
    if (key.query() == null) {
      throw new ConfigurationException(path + ".key.query must not be null");
    }
    boolean rawQuery = key.query().isEmpty();
    boolean orderIndependent = false;
    Set<String> selectedQueryNames = new HashSet<>();
    if (key.query().isPresent()) {
      ConfigurationModel.QueryKeyConfiguration query = key.query().orElseThrow();
      if (query.include() == null) {
        throw new ConfigurationException(path + ".key.query.include must not be null");
      }
      orderIndependent = query.orderIndependent();
      for (String name : query.include()) {
        String normalized = normalizeConfiguredQueryName(name, path + ".key.query.include");
        if (!selectedQueryNames.add(normalized)) {
          throw new ConfigurationException(
              path + ".key.query.include contains semantically duplicate value '" + name + "'");
        }
      }
    }

    ConfigurationModel.MatchConfiguration match = route.match();
    if (match == null) {
      throw new ConfigurationException(path + ".match must not be null");
    }
    if (match.method() == null || match.host() == null || match.trust() == null) {
      throw new ConfigurationException(path + ".match optional constraints must not be null");
    }
    if (match.path() == null || match.path().isBlank()) {
      throw new ConfigurationException(path + ".match.path must not be blank");
    }

    boolean includeMethod = match.method().isEmpty();
    boolean includeHost = match.host().isEmpty();
    boolean includeTrust = match.trust().isEmpty();
    boolean includeFullPath = hasCatchAll(match.path());
    boolean includeQuery = rawQuery || !selectedQueryNames.isEmpty();
    List<String> dimensions = new ArrayList<>();
    if (includeMethod) {
      dimensions.add(METHOD);
    }
    if (includeHost) {
      dimensions.add(HOST);
    }
    if (includeTrust) {
      dimensions.add(TRUST);
    }
    if (includeFullPath) {
      dimensions.add(PATH);
    }
    for (String capture : orderedCaptures) {
      dimensions.add(pathDimension(capture));
    }
    if (includeQuery) {
      dimensions.add(QUERY);
    }
    dimensions.add(BODY_SHA256);

    return new SemanticKeyPlan(
        orderedCaptures,
        selectedQueryNames,
        rawQuery,
        orderIndependent,
        includeMethod,
        includeHost,
        includeTrust,
        includeFullPath,
        includeQuery,
        Canonicalizers.byDimensions(dimensions));
  }

  Canonicalizer canonicalizer() {
    return canonicalizer;
  }

  Operation operation(RequestDescriptor request, Map<String, String> captures) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(captures, "captures");
    Map<String, List<String>> dimensions = new LinkedHashMap<>();
    if (includeMethod) {
      dimensions.put(METHOD, List.of(singleAttribute(request, METHOD)));
    }
    if (includeHost) {
      dimensions.put(HOST, List.of(singleAttribute(request, HOST).toLowerCase(Locale.ROOT)));
    }
    if (includeTrust) {
      dimensions.put(TRUST, List.of(request.trustLevel().name()));
    }
    if (includeFullPath) {
      dimensions.put(PATH, List.of(singleAttribute(request, PATH)));
    }
    for (String capture : pathCaptures) {
      String value = captures.get(capture);
      if (value == null || value.isEmpty()) {
        throw new IllegalArgumentException("missing matched path capture " + capture);
      }
      dimensions.put(pathDimension(capture), List.of(normalizePathCapture(value)));
    }
    if (includeQuery) {
      Optional<String> query = optionalSingleAttribute(request, QUERY);
      if (rawQuery) {
        query.ifPresent(value -> dimensions.put(QUERY, List.of(value)));
      } else {
        List<String> selected = selectedQueryPairs(query.orElse(""));
        if (!selected.isEmpty()) {
          dimensions.put(QUERY, selected);
        }
      }
    }
    dimensions.put(BODY_SHA256, List.of(normalizeSha256(singleAttribute(request, BODY_SHA256))));
    return new Operation("http.request", dimensions);
  }

  private List<String> selectedQueryPairs(String query) {
    if (query.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String pair : query.split("&", -1)) {
      int equals = pair.indexOf('=');
      String rawName = equals < 0 ? pair : pair.substring(0, equals);
      String name = normalizeQueryComponent(rawName);
      if (!selectedQueryNames.contains(name)) {
        continue;
      }
      String token;
      if (equals < 0) {
        token = packed(name) + "0";
      } else {
        String value = normalizeQueryComponent(pair.substring(equals + 1));
        token = packed(name) + "1" + packed(value);
      }
      result.add(token);
    }
    if (orderIndependentQuery) {
      result.sort(String::compareTo);
    }
    return List.copyOf(result);
  }

  private static String normalizeConfiguredQueryName(String value, String path)
      throws ConfigurationException {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(path + " contains a blank query name");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21
          || character > 0x7e
          || character == '&'
          || character == '='
          || character == '#') {
        throw new ConfigurationException(path + " contains an invalid query name '" + value + "'");
      }
    }
    try {
      return normalizeQueryComponent(value);
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException(path + " contains an invalid query name '" + value + "'", exception);
    }
  }

  private static String normalizePathCapture(String value) {
    String normalized = normalizePercentEncoding(value);
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
      throw new IllegalArgumentException("matched path capture contains a forbidden encoded value");
    }
    return normalized;
  }

  private static String normalizeQueryComponent(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21 || character > 0x7e || character == '#') {
        throw new IllegalArgumentException("query contains an invalid character");
      }
    }
    return normalizePercentEncoding(value);
  }

  private static String normalizePercentEncoding(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character != '%') {
        result.append(character);
        continue;
      }
      if (index + 2 >= value.length()) {
        throw new IllegalArgumentException("malformed percent encoding");
      }
      int high = Character.digit(value.charAt(index + 1), 16);
      int low = Character.digit(value.charAt(index + 2), 16);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException("malformed percent encoding");
      }
      char decoded = (char) ((high << 4) | low);
      if (isUnreserved(decoded)) {
        result.append(decoded);
      } else {
        result.append('%');
        result.append(Character.toUpperCase(value.charAt(index + 1)));
        result.append(Character.toUpperCase(value.charAt(index + 2)));
      }
      index += 2;
    }
    return result.toString();
  }

  private static boolean isUnreserved(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9'
        || character == '-'
        || character == '.'
        || character == '_'
        || character == '~';
  }

  private static String normalizeSha256(String value) {
    if (value.length() != 64) {
      throw new IllegalArgumentException("body-sha256 must contain 64 hexadecimal characters");
    }
    for (int index = 0; index < value.length(); index++) {
      if (Character.digit(value.charAt(index), 16) < 0) {
        throw new IllegalArgumentException("body-sha256 must contain 64 hexadecimal characters");
      }
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String singleAttribute(RequestDescriptor request, String name) {
    List<String> values = request.attributes().get(name);
    if (values == null || values.size() != 1) {
      throw new IllegalArgumentException(name + " must contain exactly one value");
    }
    return Objects.requireNonNull(values.getFirst(), name + " value");
  }

  private static Optional<String> optionalSingleAttribute(RequestDescriptor request, String name) {
    List<String> values = request.attributes().get(name);
    if (values == null) {
      return Optional.empty();
    }
    if (values.size() != 1) {
      throw new IllegalArgumentException(name + " must contain exactly one value");
    }
    return Optional.of(Objects.requireNonNull(values.getFirst(), name + " value"));
  }

  private static boolean hasCatchAll(String template) {
    return "/**".equals(template) || template.endsWith("/**");
  }

  private static String pathDimension(String capture) {
    return "path:" + capture;
  }

  private static String packed(String value) {
    return value.length() + ":" + value;
  }
}
