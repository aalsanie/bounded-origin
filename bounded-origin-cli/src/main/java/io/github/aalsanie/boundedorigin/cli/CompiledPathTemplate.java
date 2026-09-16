package io.github.aalsanie.boundedorigin.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CompiledPathTemplate {
  private final List<Segment> segments;
  private final boolean catchAll;

  private CompiledPathTemplate(List<Segment> segments, boolean catchAll) {
    this.segments = List.copyOf(segments);
    this.catchAll = catchAll;
  }

  static CompiledPathTemplate compile(String template, String path) throws ConfigurationException {
    if (template == null || template.isBlank()) {
      throw new ConfigurationException(path + " must not be blank");
    }
    validateTemplateCharacters(template, path);
    if (!template.startsWith("/")) {
      throw new ConfigurationException(path + " must start with '/'");
    }
    if ("/".equals(template)) {
      return new CompiledPathTemplate(List.of(), false);
    }
    if (template.endsWith("/")) {
      throw new ConfigurationException(path + " must not end with '/'");
    }

    String[] rawSegments = template.substring(1).split("/", -1);
    if (rawSegments.length > ConfigurationLimits.MAX_ROUTE_SEGMENTS) {
      throw new ConfigurationException(
          path + " exceeds maximum segment count " + ConfigurationLimits.MAX_ROUTE_SEGMENTS);
    }
    List<Segment> segments = new ArrayList<>(rawSegments.length);
    Set<String> captureNames = new HashSet<>();
    boolean catchAll = false;
    for (int index = 0; index < rawSegments.length; index++) {
      String segment = rawSegments[index];
      String segmentPath = path + " segment " + index;
      if (segment.isEmpty()) {
        throw new ConfigurationException(path + " contains an empty path segment");
      }
      if ("**".equals(segment)) {
        if (index != rawSegments.length - 1) {
          throw new ConfigurationException(path + " catch-all '**' must be terminal");
        }
        catchAll = true;
        continue;
      }
      if (segment.indexOf('*') >= 0) {
        throw new ConfigurationException(segmentPath + " contains unsupported wildcard syntax");
      }
      if (segment.startsWith("{") && segment.endsWith("}")) {
        String name = segment.substring(1, segment.length() - 1);
        validateCaptureName(name, segmentPath);
        if (!captureNames.add(name)) {
          throw new ConfigurationException(path + " contains duplicate capture '" + name + "'");
        }
        segments.add(Segment.capture(name));
        continue;
      }
      if (segment.indexOf('{') >= 0 || segment.indexOf('}') >= 0) {
        throw new ConfigurationException(segmentPath + " contains malformed capture syntax");
      }
      validateLiteralSegment(segment, segmentPath);
      segments.add(Segment.literal(segment));
    }
    return new CompiledPathTemplate(segments, catchAll);
  }

  Optional<Map<String, String>> match(List<String> requestSegments) {
    if ((!catchAll && requestSegments.size() != segments.size())
        || (catchAll && requestSegments.size() < segments.size())) {
      return Optional.empty();
    }
    Map<String, String> captures = null;
    for (int index = 0; index < segments.size(); index++) {
      String value = requestSegments.get(index);
      Segment segment = segments.get(index);
      if (segment.captureName != null) {
        if (value.isEmpty()) {
          return Optional.empty();
        }
        if (captures == null) {
          captures = new LinkedHashMap<>();
        }
        captures.put(segment.captureName, value);
      } else if (!segment.literal.equals(value)) {
        return Optional.empty();
      }
    }
    return Optional.of(captures == null ? Map.of() : Map.copyOf(captures));
  }

  Set<String> captureNames() {
    Set<String> result = new LinkedHashSet<>();
    for (Segment segment : segments) {
      if (segment.captureName != null) {
        result.add(segment.captureName);
      }
    }
    return Set.copyOf(result);
  }

  boolean overlaps(CompiledPathTemplate other) {
    int shared = Math.min(segments.size(), other.segments.size());
    for (int index = 0; index < shared; index++) {
      if (!segments.get(index).overlaps(other.segments.get(index))) {
        return false;
      }
    }
    if (!catchAll && !other.catchAll) {
      return segments.size() == other.segments.size();
    }
    if (catchAll && other.catchAll) {
      return true;
    }
    return catchAll
        ? other.segments.size() >= segments.size()
        : segments.size() >= other.segments.size();
  }

  private static void validateTemplateCharacters(String template, String path)
      throws ConfigurationException {
    for (int index = 0; index < template.length(); index++) {
      char character = template.charAt(index);
      if (character < 0x21
          || character > 0x7e
          || character == '?'
          || character == '#'
          || character == '\\') {
        throw new ConfigurationException(path + " contains an invalid path character");
      }
    }
  }

  private static void validateCaptureName(String name, String path) throws ConfigurationException {
    if (name.isEmpty() || !isCaptureStart(name.charAt(0))) {
      throw new ConfigurationException(path + " contains an invalid capture name");
    }
    for (int index = 1; index < name.length(); index++) {
      char character = name.charAt(index);
      if (!isCaptureStart(character) && !Character.isDigit(character) && character != '-') {
        throw new ConfigurationException(path + " contains an invalid capture name");
      }
    }
  }

  private static void validateLiteralSegment(String segment, String path)
      throws ConfigurationException {
    validatePercentEncoding(segment, path);
    String lower = segment.toLowerCase(Locale.ROOT);
    if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00")) {
      throw new ConfigurationException(path + " contains a forbidden encoded path value");
    }
    String decodedDots = decodeEncodedDots(segment);
    if (".".equals(decodedDots) || "..".equals(decodedDots)) {
      throw new ConfigurationException(path + " contains a forbidden dot path segment");
    }
  }

  private static void validatePercentEncoding(String value, String path)
      throws ConfigurationException {
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) != '%') {
        continue;
      }
      if (index + 2 >= value.length()
          || Character.digit(value.charAt(index + 1), 16) < 0
          || Character.digit(value.charAt(index + 2), 16) < 0) {
        throw new ConfigurationException(path + " contains malformed percent encoding");
      }
      index += 2;
    }
  }

  private static String decodeEncodedDots(String segment) {
    StringBuilder decoded = new StringBuilder(segment.length());
    for (int index = 0; index < segment.length(); index++) {
      if (index + 2 < segment.length()
          && segment.charAt(index) == '%'
          && segment.charAt(index + 1) == '2'
          && (segment.charAt(index + 2) == 'e' || segment.charAt(index + 2) == 'E')) {
        decoded.append('.');
        index += 2;
      } else {
        decoded.append(segment.charAt(index));
      }
    }
    return decoded.toString();
  }

  private static boolean isCaptureStart(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character == '_';
  }

  private static final class Segment {
    private final String literal;
    private final String captureName;

    private Segment(String literal, String captureName) {
      this.literal = literal;
      this.captureName = captureName;
    }

    static Segment literal(String value) {
      return new Segment(value, null);
    }

    static Segment capture(String name) {
      return new Segment(null, name);
    }

    boolean overlaps(Segment other) {
      return literal == null || other.literal == null || literal.equals(other.literal);
    }
  }
}
