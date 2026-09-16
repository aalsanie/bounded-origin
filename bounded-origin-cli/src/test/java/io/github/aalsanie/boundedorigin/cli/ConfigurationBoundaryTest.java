package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigurationBoundaryTest {
  @TempDir Path tempDirectory;

  private final YamlConfigurationLoader loader = new YamlConfigurationLoader();

  @ParameterizedTest
  @MethodSource("invalidNumbers")
  void rejectsInvalidNumericBounds(String target, String replacement, String expected)
      throws Exception {
    assertFailure(
        ConfigurationTestSupport.replace(ConfigurationTestSupport.VALID, target, replacement),
        expected);
  }

  static Stream<Arguments> invalidNumbers() {
    return Stream.of(
        Arguments.of("      version: 1", "      version: -1", "version must be non-negative"),
        Arguments.of(
            "      version: 1",
            "      version: 9223372036854775808",
            "outside the signed 64-bit range"),
        Arguments.of(
            "      precedence: 100",
            "      precedence: 2147483648",
            "outside the signed 32-bit range"),
        Arguments.of(
            "    max-bytes: 1048576", "    max-bytes: 0", "max-bytes must be positive"),
        Arguments.of(
            "    max-artifact-bytes: 262144",
            "    max-artifact-bytes: -1",
            "max-artifact-bytes must be non-negative"),
        Arguments.of(
            "    max-artifact-bytes: 262144",
            "    max-artifact-bytes: 2097152",
            "max-artifact-bytes must not exceed max-bytes"),
        Arguments.of(
            "        max-active: 4", "        max-active: 0", "max-active must be positive"),
        Arguments.of(
            "        max-queued: 16", "        max-queued: -1", "max-queued must be non-negative"),
        Arguments.of(
            "        max-result-bytes: 262144",
            "        max-result-bytes: 0",
            "max-result-bytes must be positive"),
        Arguments.of(
            "    version: 1", "    version: -1", "fallback.version must be non-negative"));
  }

  @ParameterizedTest
  @MethodSource("invalidDurations")
  void rejectsInvalidDurations(String replacement, String expected) throws Exception {
    String yaml =
        ConfigurationTestSupport.VALID.replace(
            "        max-execution-duration: PT10S", replacement);
    assertFailure(yaml, expected);
  }

  static Stream<Arguments> invalidDurations() {
    return Stream.of(
        Arguments.of("        max-execution-duration: nope", "ISO-8601 duration"),
        Arguments.of("        max-execution-duration: PT0S", "positive duration"),
        Arguments.of("        max-execution-duration: -PT1S", "positive duration"),
        Arguments.of("        max-execution-duration: 10", "must be a string"));
  }

  @Test
  void rejectsUnsupportedEnums() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace(
            "      strategy: MATERIALIZE", "      strategy: UNKNOWN"),
        "unsupported value UNKNOWN");
    assertFailure(
        ConfigurationTestSupport.VALID.replace(
            "        trust: UNTRUSTED", "        trust: UNKNOWN"),
        "unsupported value UNKNOWN");
  }

  @Test
  void rejectsBlankRequiredStringsAndScalarGatewayValues() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace("      id: render", "      id: '   '"),
        "id must not be blank");
    assertFailure(
        ConfigurationTestSupport.VALID.replace(
            "    origin.host: origin.internal", "    origin.host: ''"),
        "origin.host must not be blank");
    assertFailure(
        ConfigurationTestSupport.VALID.replace(
            "    origin.host: origin.internal", "    origin.host: [origin.internal]"),
        "string, boolean, or integer scalar");
  }

  @Test
  void acceptsIntegerAndBooleanGatewayScalars() throws Exception {
    var configuration =
        loader.load(ConfigurationTestSupport.write(tempDirectory, ConfigurationTestSupport.VALID));
    assertEquals("8080", configuration.gateway().get("origin.port"));
    assertEquals("false", configuration.gateway().get("forwarded.trust"));
  }

  @Test
  void rejectsDuplicateKeyDimensions() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace("        path: [id]", "        path: [id, id]"),
        "duplicate value id");
    assertFailure(
        ConfigurationTestSupport.VALID.replace(
            "          include: [variant]", "          include: [variant, variant]"),
        "duplicate value variant");
  }

  @Test
  void suppliesDefaultsForOptionalKeyAndClientStructures() throws Exception {
    String yaml =
        ConfigurationTestSupport.VALID
            .replace("        path: [id]\n", "")
            .replace(
                "        query:\n          include: [variant]\n          order-independent: true\n",
                "        query: {}\n")
            .replace("          parameters:\n            mode: strict\n", "");

    var configuration = loader.load(ConfigurationTestSupport.write(tempDirectory, yaml));

    var key = configuration.routes().getFirst().key().orElseThrow();
    assertTrue(key.path().isEmpty());
    assertTrue(key.query().include().isEmpty());
    assertFalse(key.query().orderIndependent());
    assertTrue(
        configuration.routes().get(1).clientComputation().orElseThrow().parameters().isEmpty());
  }

  @Test
  void supportsKeyWithoutQueryAndQueryWithoutOrderFlag() throws Exception {
    String withoutQuery =
        ConfigurationTestSupport.VALID.replace(
            "        query:\n          include: [variant]\n          order-independent: true\n", "");
    var first = loader.load(ConfigurationTestSupport.write(tempDirectory, withoutQuery));
    assertTrue(first.routes().getFirst().key().orElseThrow().query().include().isEmpty());

    String withoutOrder =
        ConfigurationTestSupport.VALID.replace("          order-independent: true\n", "");
    var second = loader.load(ConfigurationTestSupport.write(tempDirectory, withoutOrder));
    assertFalse(second.routes().getFirst().key().orElseThrow().query().orderIndependent());
  }

  @Test
  void rejectsTooManyRoutes() throws Exception {
    String route =
        """
          - id: r%d
            version: 0
            precedence: 0
            match:
              path: /r%d
            strategy: DENY
        """;
    StringBuilder routes = new StringBuilder("routes:\n");
    for (int index = 0; index <= ConfigurationLimits.MAX_ROUTES; index++) {
      routes.append(route.formatted(index, index));
    }
    String yaml =
        """
        schema: 1
        gateway:
          origin.host: localhost
          origin.port: 8080
          temporary.directory: /tmp/work
        store:
          directory: /tmp/store
          max-bytes: 1
          max-artifact-bytes: 0
        """
            + routes
            + """
        fallback:
          id: fallback
          version: 0
          precedence: 0
          strategy: DENY
        """;
    assertFailure(yaml, "configuration.routes exceeds maximum entries");
  }

  private void assertFailure(String yaml, String expected) throws Exception {
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> loader.load(ConfigurationTestSupport.write(tempDirectory, yaml)));
    assertTrue(
        exception.getMessage().contains(expected),
        () -> "expected <" + expected + "> in <" + exception.getMessage() + ">");
  }
}
