package io.github.aalsanie.boundedorigin.cli;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigurationValues {
  private ConfigurationValues() {}

  static Map<String, Object> mapping(Object value, String path) throws ConfigurationException {
    if (!(value instanceof Map<?, ?> source)) {
      throw type(path, "mapping");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new ConfigurationException(path + " contains a non-string or blank key");
      }
      result.put(key, entry.getValue());
    }
    return Collections.unmodifiableMap(result);
  }

  static List<Object> sequence(Object value, String path, int maximum)
      throws ConfigurationException {
    if (!(value instanceof List<?> source)) {
      throw type(path, "sequence");
    }
    if (source.size() > maximum) {
      throw new ConfigurationException(path + " exceeds maximum entries " + maximum);
    }
    return Collections.unmodifiableList(new ArrayList<>(source));
  }

  static Object required(Map<String, Object> values, String key, String path)
      throws ConfigurationException {
    if (!values.containsKey(key)) {
      throw new ConfigurationException(path + "." + key + " is required");
    }
    Object value = values.get(key);
    if (value == null) {
      throw new ConfigurationException(path + "." + key + " must not be null");
    }
    return value;
  }

  static void rejectUnknown(Map<String, Object> values, Set<String> allowed, String path)
      throws ConfigurationException {
    for (String key : values.keySet()) {
      if (!allowed.contains(key)) {
        throw new ConfigurationException(path + " contains unknown key " + key);
      }
    }
  }

  static String nonBlankString(Object value, String path) throws ConfigurationException {
    if (!(value instanceof String string)) {
      throw type(path, "string");
    }
    if (string.isBlank()) {
      throw new ConfigurationException(path + " must not be blank");
    }
    return string;
  }

  static boolean booleanValue(Object value, String path) throws ConfigurationException {
    if (!(value instanceof Boolean bool)) {
      throw type(path, "boolean");
    }
    return bool;
  }

  static long longValue(Object value, String path) throws ConfigurationException {
    if (!(value instanceof Number number) || !isIntegral(number.toString())) {
      throw type(path, "integer");
    }
    try {
      return Long.parseLong(number.toString());
    } catch (NumberFormatException exception) {
      throw new ConfigurationException(path + " is outside the signed 64-bit range", exception);
    }
  }

  static long nonNegativeLong(Object value, String path) throws ConfigurationException {
    long parsed = longValue(value, path);
    if (parsed < 0) {
      throw new ConfigurationException(path + " must be non-negative");
    }
    return parsed;
  }

  static long positiveLong(Object value, String path) throws ConfigurationException {
    long parsed = longValue(value, path);
    if (parsed <= 0) {
      throw new ConfigurationException(path + " must be positive");
    }
    return parsed;
  }

  static int integerValue(Object value, String path) throws ConfigurationException {
    long parsed = longValue(value, path);
    if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
      throw new ConfigurationException(path + " is outside the signed 32-bit range");
    }
    return (int) parsed;
  }

  static int nonNegativeInt(Object value, String path) throws ConfigurationException {
    int parsed = integerValue(value, path);
    if (parsed < 0) {
      throw new ConfigurationException(path + " must be non-negative");
    }
    return parsed;
  }

  static int positiveInt(Object value, String path) throws ConfigurationException {
    int parsed = integerValue(value, path);
    if (parsed <= 0) {
      throw new ConfigurationException(path + " must be positive");
    }
    return parsed;
  }

  static Duration positiveDuration(Object value, String path) throws ConfigurationException {
    String raw = nonBlankString(value, path);
    try {
      Duration duration = Duration.parse(raw);
      if (duration.isZero() || duration.isNegative()) {
        throw new ConfigurationException(path + " must be a positive duration");
      }
      return duration;
    } catch (DateTimeParseException exception) {
      throw new ConfigurationException(path + " must be an ISO-8601 duration", exception);
    }
  }

  static <E extends Enum<E>> E enumValue(Object value, String path, Class<E> type)
      throws ConfigurationException {
    String raw = nonBlankString(value, path);
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException exception) {
      throw new ConfigurationException(path + " contains unsupported value " + raw, exception);
    }
  }

  static List<String> uniqueStringList(Object value, String path, int maximum)
      throws ConfigurationException {
    List<Object> source = sequence(value, path, maximum);
    List<String> result = new ArrayList<>(source.size());
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < source.size(); index++) {
      String item = nonBlankString(source.get(index), path + "[" + index + "]");
      if (!seen.add(item)) {
        throw new ConfigurationException(path + " contains duplicate value " + item);
      }
      result.add(item);
    }
    return List.copyOf(result);
  }

  static Map<String, String> stringMap(Object value, String path, int maximum)
      throws ConfigurationException {
    Map<String, Object> source = mapping(value, path);
    if (source.size() > maximum) {
      throw new ConfigurationException(path + " exceeds maximum entries " + maximum);
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      String string = nonBlankString(entry.getValue(), path + "." + entry.getKey());
      result.put(entry.getKey(), string);
    }
    return Map.copyOf(result);
  }

  static String scalarText(Object value, String path) throws ConfigurationException {
    if (value instanceof String string) {
      if (string.isBlank()) {
        throw new ConfigurationException(path + " must not be blank");
      }
      return string;
    }
    if (value instanceof Boolean bool) {
      return Boolean.toString(bool);
    }
    if (value instanceof Number number && isIntegral(number.toString())) {
      return number.toString();
    }
    throw type(path, "string, boolean, or integer scalar");
  }

  private static boolean isIntegral(String value) {
    if (value.isEmpty()) {
      return false;
    }
    int index = value.charAt(0) == '-' ? 1 : 0;
    if (index == value.length()) {
      return false;
    }
    for (; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') {
        return false;
      }
    }
    return true;
  }

  private static ConfigurationException type(String path, String expected) {
    return new ConfigurationException(path + " must be a " + expected);
  }
}
