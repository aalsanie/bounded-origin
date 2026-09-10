package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestDescriptorTest {
  @Test
  void copiesAttributesDeeply() {
    List<String> values = new ArrayList<>(List.of("one"));
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("id", values);

    RequestDescriptor descriptor =
        new RequestDescriptor("render", attributes, TrustLevel.UNTRUSTED);
    values.add("two");
    attributes.put("other", List.of("x"));

    assertEquals(Map.of("id", List.of("one")), descriptor.attributes());
    assertThrows(
        UnsupportedOperationException.class, () -> descriptor.attributes().put("x", List.of("y")));
    assertThrows(
        UnsupportedOperationException.class, () -> descriptor.attributes().get("id").add("y"));
  }

  @Test
  void rejectsMalformedValues() {
    assertThrows(
        NullPointerException.class,
        () -> new RequestDescriptor(null, Map.of(), TrustLevel.UNTRUSTED));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestDescriptor(" ", Map.of(), TrustLevel.UNTRUSTED));
    assertThrows(
        NullPointerException.class,
        () -> new RequestDescriptor("render", null, TrustLevel.UNTRUSTED));
    assertThrows(NullPointerException.class, () -> new RequestDescriptor("render", Map.of(), null));

    Map<String, List<String>> blankKey = new LinkedHashMap<>();
    blankKey.put(" ", List.of("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestDescriptor("render", blankKey, TrustLevel.UNTRUSTED));

    Map<String, List<String>> nullValues = new LinkedHashMap<>();
    nullValues.put("id", null);
    assertThrows(
        NullPointerException.class,
        () -> new RequestDescriptor("render", nullValues, TrustLevel.UNTRUSTED));

    Map<String, List<String>> emptyValues = new LinkedHashMap<>();
    emptyValues.put("id", List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RequestDescriptor("render", emptyValues, TrustLevel.UNTRUSTED));

    Map<String, List<String>> nullEntry = new LinkedHashMap<>();
    List<String> withNull = new ArrayList<>();
    withNull.add(null);
    nullEntry.put("id", withNull);
    assertThrows(
        NullPointerException.class,
        () -> new RequestDescriptor("render", nullEntry, TrustLevel.UNTRUSTED));
  }
}
