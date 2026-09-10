package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientComputationTest {
  @Test
  void copiesParameters() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("mode", "fast");
    ClientComputation computation = new ClientComputation("transform", "v2", parameters);
    parameters.put("mode", "slow");

    assertEquals(Map.of("mode", "fast"), computation.parameters());
    assertThrows(UnsupportedOperationException.class, () -> computation.parameters().put("x", "y"));
  }

  @Test
  void rejectsMalformedValues() {
    assertThrows(NullPointerException.class, () -> new ClientComputation(null, "v1", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new ClientComputation(" ", "v1", Map.of()));
    assertThrows(NullPointerException.class, () -> new ClientComputation("x", null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new ClientComputation("x", " ", Map.of()));
    assertThrows(NullPointerException.class, () -> new ClientComputation("x", "v1", null));

    Map<String, String> blankKey = new LinkedHashMap<>();
    blankKey.put(" ", "value");
    assertThrows(IllegalArgumentException.class, () -> new ClientComputation("x", "v1", blankKey));

    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("key", null);
    assertThrows(NullPointerException.class, () -> new ClientComputation("x", "v1", nullValue));
  }
}
