package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigurationDecoderTest {
  @TempDir Path tempDirectory;

  private final YamlConfigurationLoader loader = new YamlConfigurationLoader();

  @ParameterizedTest
  @MethodSource("unknownKeys")
  void rejectsUnknownKeys(String target, String replacement, String expected) throws Exception {
    String yaml =
        ConfigurationTestSupport.replace(ConfigurationTestSupport.VALID, target, replacement);
    assertFailure(yaml, expected);
  }

  static Stream<Arguments> unknownKeys() {
    return Stream.of(
        Arguments.of("schema: 1", "schema: 1\nunknown: true", "unknown key unknown"),
        Arguments.of(
            "    origin.host: origin.internal",
            "    origin.host: origin.internal\n    unknown.gateway: true",
            "unknown key unknown.gateway"),
        Arguments.of(
            "    max-artifact-bytes: 262144",
            "    max-artifact-bytes: 262144\n    unknown: true",
            "configuration.store contains unknown key unknown"),
        Arguments.of(
            "      strategy: MATERIALIZE",
            "      strategy: MATERIALIZE\n      unknown: true",
            "configuration.routes[0] contains unknown key unknown"),
        Arguments.of(
            "        trust: UNTRUSTED",
            "        trust: UNTRUSTED\n        unknown: true",
            "configuration.routes[0].match contains unknown key unknown"),
        Arguments.of(
            "          order-independent: true",
            "          order-independent: true\n          unknown: true",
            "configuration.routes[0].key.query contains unknown key unknown"),
        Arguments.of(
            "        path: [id]",
            "        path: [id]\n        unknown: true",
            "configuration.routes[0].key contains unknown key unknown"),
        Arguments.of(
            "        max-result-bytes: 262144",
            "        max-result-bytes: 262144\n        unknown: true",
            "configuration.routes[0].budget contains unknown key unknown"),
        Arguments.of(
            "            mode: strict",
            "            mode: strict\n        unknown: true",
            "configuration.routes[1].client-computation contains unknown key unknown"),
        Arguments.of(
            "    strategy: DENY",
            "    strategy: DENY\n    unknown: true",
            "configuration.fallback contains unknown key unknown"));
  }

  @ParameterizedTest
  @MethodSource("requiredFieldFailures")
  void rejectsMissingOrNullRequiredFields(String target, String replacement, String expected)
      throws Exception {
    String yaml =
        ConfigurationTestSupport.replace(ConfigurationTestSupport.VALID, target, replacement);
    assertFailure(yaml, expected);
  }

  static Stream<Arguments> requiredFieldFailures() {
    return Stream.of(
        Arguments.of("schema: 1", "schema: null", "configuration.schema must not be null"),
        Arguments.of("schema: 1\n", "", "configuration.schema is required"),
        Arguments.of("gateway:\n", "gateway: null\n", "configuration.gateway must not be null"),
        Arguments.of("store:\n", "store: null\n", "configuration.store must not be null"),
        Arguments.of("routes:\n", "routes: null\n", "configuration.routes must not be null"),
        Arguments.of("fallback:\n", "fallback: null\n", "configuration.fallback must not be null"),
        Arguments.of(
            "    origin.host: origin.internal\n",
            "",
            "configuration.gateway.origin.host is required"),
        Arguments.of(
            "    origin.port: 8080\n", "", "configuration.gateway.origin.port is required"),
        Arguments.of(
            "    temporary.directory: /tmp/bounded-origin\n",
            "",
            "configuration.gateway.temporary.directory is required"),
        Arguments.of(
            "    directory: /var/lib/bounded-origin\n",
            "",
            "configuration.store.directory is required"),
        Arguments.of(
            "    max-bytes: 1048576\n", "", "configuration.store.max-bytes is required"),
        Arguments.of(
            "    max-artifact-bytes: 262144\n",
            "",
            "configuration.store.max-artifact-bytes is required"),
        Arguments.of("      id: render\n", "", "configuration.routes[0].id is required"),
        Arguments.of(
            "      version: 1\n", "", "configuration.routes[0].version is required"),
        Arguments.of(
            "      precedence: 100\n", "", "configuration.routes[0].precedence is required"),
        Arguments.of(
            "      match:\n",
            "      match: null\n",
            "configuration.routes[0].match must not be null"),
        Arguments.of(
            "        path: /render/{id}\n", "", "configuration.routes[0].match.path is required"),
        Arguments.of(
            "      strategy: MATERIALIZE\n", "", "configuration.routes[0].strategy is required"),
        Arguments.of(
            "    id: default-deny\n", "", "configuration.fallback.id is required"),
        Arguments.of(
            "    version: 1\n", "", "configuration.fallback.version is required"),
        Arguments.of(
            "    precedence: -2147483648\n", "", "configuration.fallback.precedence is required"),
        Arguments.of(
            "    strategy: DENY\n", "", "configuration.fallback.strategy is required"));
  }

  @ParameterizedTest
  @MethodSource("typeFailures")
  void rejectsWrongScalarAndCollectionTypes(String target, String replacement, String expected)
      throws Exception {
    String yaml =
        ConfigurationTestSupport.replace(ConfigurationTestSupport.VALID, target, replacement);
    assertFailure(yaml, expected);
  }

  static Stream<Arguments> typeFailures() {
    return Stream.of(
        Arguments.of("schema: 1", "schema: true", "configuration.schema must be a integer"),
        Arguments.of("gateway:\n", "gateway: []\n", "configuration.gateway must be a mapping"),
        Arguments.of("store:\n", "store: []\n", "configuration.store must be a mapping"),
        Arguments.of("routes:\n", "routes: {}\n", "configuration.routes must be a sequence"),
        Arguments.of("fallback:\n", "fallback: []\n", "configuration.fallback must be a mapping"),
        Arguments.of(
            "    directory: /var/lib/bounded-origin",
            "    directory: 1",
            "configuration.store.directory must be a string"),
        Arguments.of(
            "    max-bytes: 1048576",
            "    max-bytes: true",
            "configuration.store.max-bytes must be a integer"),
        Arguments.of(
            "      version: 1",
            "      version: 1.5",
            "configuration.routes[0].version must be a integer"),
        Arguments.of(
            "      strategy: MATERIALIZE",
            "      strategy: 1",
            "configuration.routes[0].strategy must be a string"),
        Arguments.of(
            "        path: /render/{id}",
            "        path: [bad]",
            "configuration.routes[0].match.path must be a string"),
        Arguments.of(
            "        path: [id]",
            "        path: id",
            "configuration.routes[0].key.path must be a sequence"),
        Arguments.of(
            "          order-independent: true",
            "          order-independent: yes",
            "configuration.routes[0].key.query.order-independent must be a boolean"),
        Arguments.of(
            "        max-active: 4",
            "        max-active: false",
            "configuration.routes[0].budget.max-active must be a integer"),
        Arguments.of(
            "            mode: strict",
            "            mode: 1",
            "configuration.routes[1].client-computation.parameters.mode must be a string"));
  }

  @Test
  void rejectsUnsupportedSchema() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace("schema: 1", "schema: 2"), "schema must be 1");
  }

  @Test
  void rejectsNonDenyFallback() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace(
            "    strategy: DENY", "    strategy: MATERIALIZE"),
        "fallback.strategy must be DENY");
  }

  @Test
  void rejectsDuplicatePolicyIdsIncludingFallback() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace("    id: default-deny", "    id: render"),
        "duplicate policy id render");
  }

  @Test
  void rejectsDuplicateRouteIds() throws Exception {
    assertFailure(
        ConfigurationTestSupport.VALID.replace("      id: client", "      id: render"),
        "duplicate policy id render");
  }

  @Test
  void acceptsSignedPrecedenceBounds() throws Exception {
    String yaml =
        ConfigurationTestSupport.VALID.replace(
            "      precedence: 100", "      precedence: 2147483647");
    var configuration = loader.load(ConfigurationTestSupport.write(tempDirectory, yaml));
    assertEquals(Integer.MAX_VALUE, configuration.routes().getFirst().precedence());
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
