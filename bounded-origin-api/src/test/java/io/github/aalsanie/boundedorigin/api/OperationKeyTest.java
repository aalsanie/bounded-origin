package io.github.aalsanie.boundedorigin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OperationKeyTest {
  @Test
  void equalityIncludesEveryIdentityComponent() {
    OperationKey key = new OperationKey("render", 3, "semantic", "m2");
    OperationKey same = new OperationKey("render", 3, "semantic", "m2");

    assertEquals(key, same);
    assertEquals(key.hashCode(), same.hashCode());
    assertNotEquals(key, new OperationKey("other", 3, "semantic", "m2"));
    assertNotEquals(key, new OperationKey("render", 4, "semantic", "m2"));
    assertNotEquals(key, new OperationKey("render", 3, "other", "m2"));
    assertNotEquals(key, new OperationKey("render", 3, "semantic", "m3"));
  }

  @Test
  void rejectsMalformedIdentity() {
    assertThrows(NullPointerException.class, () -> new OperationKey(null, 0, "x", "m"));
    assertThrows(IllegalArgumentException.class, () -> new OperationKey(" ", 0, "x", "m"));
    assertThrows(IllegalArgumentException.class, () -> new OperationKey("p", -1, "x", "m"));
    assertThrows(NullPointerException.class, () -> new OperationKey("p", 0, null, "m"));
    assertThrows(IllegalArgumentException.class, () -> new OperationKey("p", 0, " ", "m"));
    assertThrows(NullPointerException.class, () -> new OperationKey("p", 0, "x", null));
    assertThrows(IllegalArgumentException.class, () -> new OperationKey("p", 0, "x", " "));
  }
}
