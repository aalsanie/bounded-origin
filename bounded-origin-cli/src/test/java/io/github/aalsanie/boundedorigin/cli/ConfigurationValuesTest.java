package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigurationValuesTest {
  @Test
  void validatesMappingsAndSequences() throws Exception {
    assertEquals(Map.of("a", 1), ConfigurationValues.mapping(Map.of("a", 1), "root"));
    assertEquals(List.of("a"), ConfigurationValues.sequence(List.of("a"), "root", 1));

    assertMessage(() -> ConfigurationValues.mapping(List.of(), "root"), "mapping");
    assertMessage(() -> ConfigurationValues.sequence(Map.of(), "root", 1), "sequence");
    assertMessage(
        () -> ConfigurationValues.sequence(List.of(1, 2), "root", 1), "maximum entries 1");

    Map<Object, Object> nonString = new LinkedHashMap<>();
    nonString.put(1, "x");
    assertMessage(
        () -> ConfigurationValues.mapping(nonString, "root"), "non-string or blank key");

    Map<Object, Object> blank = new LinkedHashMap<>();
    blank.put(" ", "x");
    assertMessage(() -> ConfigurationValues.mapping(blank, "root"), "non-string or blank key");
  }

  @Test
  void validatesRequiredAndUnknownFields() throws Exception {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("present", "x");
    values.put("null", null);

    assertEquals("x", ConfigurationValues.required(values, "present", "root"));
    assertMessage(() -> ConfigurationValues.required(values, "missing", "root"), "is required");
    assertMessage(
        () -> ConfigurationValues.required(values, "null", "root"), "must not be null");
    ConfigurationValues.rejectUnknown(Map.of("a", 1), Set.of("a"), "root");
    assertMessage(
        () -> ConfigurationValues.rejectUnknown(Map.of("b", 1), Set.of("a"), "root"),
        "unknown key b");
  }

  @Test
  void validatesStringsBooleansAndEnums() throws Exception {
    assertEquals("x", ConfigurationValues.nonBlankString("x", "value"));
    assertTrue(ConfigurationValues.booleanValue(true, "value"));
    assertFalse(ConfigurationValues.booleanValue(false, "value"));
    assertEquals(
        ConfigurationModel.Strategy.DENY,
        ConfigurationValues.enumValue("DENY", "value", ConfigurationModel.Strategy.class));

    assertMessage(() -> ConfigurationValues.nonBlankString(1, "value"), "must be a string");
    assertMessage(() -> ConfigurationValues.nonBlankString(" ", "value"), "must not be blank");
    assertMessage(() -> ConfigurationValues.booleanValue("true", "value"), "must be a boolean");
    assertMessage(
        () -> ConfigurationValues.enumValue("NOPE", "value", ConfigurationModel.Strategy.class),
        "unsupported value NOPE");
  }

  @Test
  void validatesIntegralRanges() throws Exception {
    assertEquals(Long.MIN_VALUE, ConfigurationValues.longValue(Long.MIN_VALUE, "value"));
    assertEquals(Long.MAX_VALUE, ConfigurationValues.longValue(Long.MAX_VALUE, "value"));
    assertEquals(
        Integer.MIN_VALUE, ConfigurationValues.integerValue(Integer.MIN_VALUE, "value"));
    assertEquals(
        Integer.MAX_VALUE, ConfigurationValues.integerValue(Integer.MAX_VALUE, "value"));
    assertEquals(0L, ConfigurationValues.nonNegativeLong(0L, "value"));
    assertEquals(1L, ConfigurationValues.positiveLong(1L, "value"));
    assertEquals(0, ConfigurationValues.nonNegativeInt(0, "value"));
    assertEquals(1, ConfigurationValues.positiveInt(1, "value"));

    assertMessage(() -> ConfigurationValues.longValue(1.5d, "value"), "must be a integer");
    assertMessage(() -> ConfigurationValues.longValue("1", "value"), "must be a integer");
    assertMessage(
        () -> ConfigurationValues.nonNegativeLong(-1L, "value"), "must be non-negative");
    assertMessage(() -> ConfigurationValues.positiveLong(0L, "value"), "must be positive");
    assertMessage(
        () -> ConfigurationValues.integerValue((long) Integer.MAX_VALUE + 1L, "value"),
        "outside the signed 32-bit range");
    assertMessage(
        () -> ConfigurationValues.integerValue((long) Integer.MIN_VALUE - 1L, "value"),
        "outside the signed 32-bit range");
    assertMessage(
        () -> ConfigurationValues.nonNegativeInt(-1, "value"), "must be non-negative");
    assertMessage(() -> ConfigurationValues.positiveInt(0, "value"), "must be positive");
  }

  @Test
  void validatesDurations() throws Exception {
    assertEquals(
        Duration.ofSeconds(1), ConfigurationValues.positiveDuration("PT1S", "value"));
    assertMessage(
        () -> ConfigurationValues.positiveDuration("PT0S", "value"), "positive duration");
    assertMessage(
        () -> ConfigurationValues.positiveDuration("-PT1S", "value"), "positive duration");
    assertMessage(
        () -> ConfigurationValues.positiveDuration("bad", "value"), "ISO-8601 duration");
  }

  @Test
  void validatesUniqueListsAndStringMaps() throws Exception {
    assertEquals(
        List.of("a", "b"),
        ConfigurationValues.uniqueStringList(List.of("a", "b"), "list", 2));
    assertMessage(
        () -> ConfigurationValues.uniqueStringList(List.of("a", "a"), "list", 2),
        "duplicate value a");
    assertMessage(
        () -> ConfigurationValues.uniqueStringList(List.of("a", 1), "list", 2),
        "list[1] must be a string");

    assertEquals(
        Map.of("a", "b"), ConfigurationValues.stringMap(Map.of("a", "b"), "map", 1));
    assertMessage(
        () -> ConfigurationValues.stringMap(Map.of("a", "b", "c", "d"), "map", 1),
        "maximum entries 1");
    assertMessage(
        () -> ConfigurationValues.stringMap(Map.of("a", 1), "map", 1),
        "map.a must be a string");
  }

  @Test
  void normalizesSupportedGatewayScalars() throws Exception {
    assertEquals("text", ConfigurationValues.scalarText("text", "value"));
    assertEquals("true", ConfigurationValues.scalarText(true, "value"));
    assertEquals("42", ConfigurationValues.scalarText(42, "value"));
    assertEquals("-42", ConfigurationValues.scalarText(-42, "value"));
    assertMessage(() -> ConfigurationValues.scalarText(" ", "value"), "must not be blank");
    assertMessage(
        () -> ConfigurationValues.scalarText(1.5d, "value"),
        "string, boolean, or integer scalar");
    assertMessage(
        () -> ConfigurationValues.scalarText(List.of(), "value"),
        "string, boolean, or integer scalar");
  }

  private static void assertMessage(ThrowingRunnable runnable, String expected) {
    ConfigurationException exception = assertThrows(ConfigurationException.class, runnable::run);
    assertTrue(exception.getMessage().contains(expected));
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws ConfigurationException;
  }
}
