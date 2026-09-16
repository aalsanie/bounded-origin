package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayConfigurationValidatorTest {
  @Test
  void acceptsEveryGatewayScalarCategory() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("listen.host", "0.0.0.0");
    values.put("listen.port", "0");
    values.put("admin.host", "127.0.0.1");
    values.put("admin.port", "65535");
    values.put("origin.host", "origin.internal");
    values.put("origin.port", "1");
    values.put("temporary.directory", "/tmp/work");
    values.put("ingress.trust", "untrusted");
    values.put("forwarded.trust", "TRUE");
    values.put("event-loop.threads", "1");
    values.put("origin.max-queued", "0");
    values.put("spool.max-bytes", "1");
    values.put("http.max-request-body-bytes", "0");
    values.put("request.timeout", "PT0.001S");

    assertDoesNotThrow(() -> GatewayConfigurationValidator.validate(values));
  }

  @Test
  void rejectsInvalidHostsAndPaths() {
    assertInvalid("origin.host", "", "must not be blank");
    assertInvalid("origin.host", "bad host", "invalid characters");
    assertInvalid("origin.host", "bad\nhost", "invalid characters");
    assertInvalid("temporary.directory", "", "must not be blank");
    assertInvalid("temporary.directory", "bad\u0000path", "valid filesystem path");
  }

  @Test
  void rejectsInvalidPorts() {
    assertInvalid("listen.port", "-1", "between 0 and 65535");
    assertInvalid("listen.port", "65536", "between 0 and 65535");
    assertInvalid("origin.port", "0", "between 1 and 65535");
    assertInvalid("origin.port", "65536", "between 1 and 65535");
    assertInvalid("origin.port", "not-a-port", "must be an integer");
  }

  @Test
  void rejectsInvalidIntegerLimits() {
    assertInvalid("event-loop.threads", "0", "must be positive");
    assertInvalid("origin.max-queued", "-1", "must be non-negative");
    assertInvalid("event-loop.threads", "2147483648", "must be an integer");
  }

  @Test
  void rejectsInvalidLongLimits() {
    assertInvalid("spool.max-bytes", "0", "must be positive");
    assertInvalid("http.max-request-body-bytes", "-1", "must be non-negative");
    assertInvalid("spool.max-bytes", "9223372036854775808", "long integer");
  }

  @Test
  void rejectsInvalidDurations() {
    assertInvalid("request.timeout", "bad", "ISO-8601 duration");
    assertInvalid("request.timeout", "PT0S", "positive duration");
    assertInvalid("request.timeout", "-PT1S", "positive duration");
    assertInvalid("request.timeout", "P106751992D", "too large");
  }

  @Test
  void rejectsInvalidBooleanAndTrustValues() {
    assertInvalid("forwarded.trust", "yes", "true or false");
    assertInvalid("ingress.trust", "maybe", "TRUSTED or UNTRUSTED");
  }

  @Test
  void rejectsUnclassifiedGatewayKey() {
    assertInvalid("unexpected", "value", "unsupported gateway configuration key unexpected");
  }

  private static void assertInvalid(String key, String value, String expected) {
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> GatewayConfigurationValidator.validate(Map.of(key, value)));
    assertTrue(
        exception.getMessage().contains(expected),
        () -> "expected <" + expected + "> in <" + exception.getMessage() + ">");
  }
}
