package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationTest {
  @Test
  void copiesDimensionsDeeplyAndHasValueEquality() {
    List<String> values = new ArrayList<>(List.of("one"));
    Map<String, List<String>> dimensions = new LinkedHashMap<>();
    dimensions.put("id", values);
    Operation operation = new Operation("render", dimensions);

    values.add("two");
    dimensions.put("other", List.of("x"));

    assertEquals(new Operation("render", Map.of("id", List.of("one"))), operation);
    assertEquals(
        new Operation("render", Map.of("id", List.of("one"))).hashCode(), operation.hashCode());
    assertThrows(
        UnsupportedOperationException.class, () -> operation.dimensions().put("x", List.of("y")));
    assertThrows(
        UnsupportedOperationException.class, () -> operation.dimensions().get("id").add("y"));
  }

  @Test
  void rejectsMalformedValues() {
    assertThrows(NullPointerException.class, () -> new Operation(null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new Operation(" ", Map.of()));
    assertThrows(NullPointerException.class, () -> new Operation("render", null));

    Map<String, List<String>> blankKey = new LinkedHashMap<>();
    blankKey.put(" ", List.of("x"));
    assertThrows(IllegalArgumentException.class, () -> new Operation("render", blankKey));

    Map<String, List<String>> nullValues = new LinkedHashMap<>();
    nullValues.put("id", null);
    assertThrows(NullPointerException.class, () -> new Operation("render", nullValues));

    Map<String, List<String>> emptyValues = new LinkedHashMap<>();
    emptyValues.put("id", List.of());
    assertThrows(IllegalArgumentException.class, () -> new Operation("render", emptyValues));

    Map<String, List<String>> nullEntry = new LinkedHashMap<>();
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    nullEntry.put("id", withNull);
    assertThrows(NullPointerException.class, () -> new Operation("render", nullEntry));
  }
}
