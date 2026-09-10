package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CanonicalizersTest {
  @Test
  void canonicalizationIsIndependentOfMapInsertionOrder() {
    Canonicalizer canonicalizer = Canonicalizers.byDimensions("id", "format", "size");
    Map<String, List<String>> dimensions = new LinkedHashMap<>();
    dimensions.put("id", List.of("123"));
    dimensions.put("format", List.of("webp"));
    dimensions.put("size", List.of("large"));
    String expected = canonicalizer.canonicalize(new Operation("render", dimensions));

    Random random = new Random(938447L);
    List<String> keys = new ArrayList<>(dimensions.keySet());
    for (int iteration = 0; iteration < 2_000; iteration++) {
      Collections.shuffle(keys, random);
      Map<String, List<String>> permutation = new LinkedHashMap<>();
      for (String key : keys) {
        permutation.put(key, dimensions.get(key));
      }
      assertEquals(expected, canonicalizer.canonicalize(new Operation("render", permutation)));
    }
  }

  @Test
  void onlyExplicitDimensionsAffectIdentity() {
    Canonicalizer canonicalizer = Canonicalizers.byDimensions(List.of("id", "variant"));
    Operation left =
        new Operation(
            "render",
            Map.of("id", List.of("42"), "variant", List.of("x"), "ignored", List.of("a")));
    Operation right =
        new Operation(
            "render",
            Map.of("id", List.of("42"), "variant", List.of("x"), "ignored", List.of("b")));
    Operation changed =
        new Operation("render", Map.of("id", List.of("42"), "variant", List.of("y")));

    assertEquals(canonicalizer.canonicalize(left), canonicalizer.canonicalize(right));
    assertNotEquals(canonicalizer.canonicalize(left), canonicalizer.canonicalize(changed));
  }

  @Test
  void identityDistinguishesMissingEmptyAndOrderedValues() {
    Canonicalizer canonicalizer = Canonicalizers.byDimensions("value");
    Operation missing = new Operation("render", Map.of("other", List.of("")));
    Operation empty = new Operation("render", Map.of("value", List.of("")));
    Operation ordered = new Operation("render", Map.of("value", List.of("a", "bc")));
    Operation reordered = new Operation("render", Map.of("value", List.of("ab", "c")));

    assertNotEquals(canonicalizer.canonicalize(missing), canonicalizer.canonicalize(empty));
    assertNotEquals(canonicalizer.canonicalize(ordered), canonicalizer.canonicalize(reordered));
    assertNotEquals(
        canonicalizer.canonicalize(new Operation("render", Map.of())),
        canonicalizer.canonicalize(new Operation("other", Map.of())));
  }

  @Test
  void validatesConfigurationAndOperation() {
    assertThrows(NullPointerException.class, () -> Canonicalizers.byDimensions((String[]) null));
    assertThrows(
        NullPointerException.class, () -> Canonicalizers.byDimensions((List<String>) null));
    assertThrows(
        NullPointerException.class, () -> Canonicalizers.byDimensions(List.of("id", null)));
    assertThrows(IllegalArgumentException.class, () -> Canonicalizers.byDimensions(List.of(" ")));
    assertThrows(
        IllegalArgumentException.class, () -> Canonicalizers.byDimensions(List.of("id", "id")));
    Canonicalizer canonicalizer = Canonicalizers.byDimensions();
    assertThrows(NullPointerException.class, () -> canonicalizer.canonicalize(null));
  }
}
