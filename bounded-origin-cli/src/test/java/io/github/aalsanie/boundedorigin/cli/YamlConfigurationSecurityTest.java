package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigurationSecurityTest {
  @TempDir Path tempDirectory;

  private final YamlConfigurationLoader loader = new YamlConfigurationLoader();

  @Test
  void rejectsOversizedDocument() throws Exception {
    Path path = tempDirectory.resolve("config.yaml");
    Files.writeString(path, "x".repeat(ConfigurationLimits.MAX_BYTES + 1));

    assertMessage(path, "maximum size");
  }

  @Test
  void enforcesStructuralLimitsAtMaximumDocumentSize() throws Exception {
    Path path = tempDirectory.resolve("config.yaml");
    Files.writeString(path, "x".repeat(ConfigurationLimits.MAX_BYTES));

    assertMessage(path, "scalar");
  }

  @Test
  void rejectsDuplicateYamlKeys() throws Exception {
    String yaml =
        ConfigurationTestSupport.VALID.replace("schema: 1\n", "schema: 1\nschema: 1\n");
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml), "invalid YAML");
  }

  @Test
  void rejectsAliasesAndAnchors() throws Exception {
    String yaml =
        """
        schema: 1
        gateway: &gateway
          origin.host: localhost
          origin.port: 8080
          temporary.directory: /tmp/work
        store:
          directory: /tmp/store
          max-bytes: 10
          max-artifact-bytes: 1
        routes: []
        fallback: *gateway
        """;
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> loader.load(ConfigurationTestSupport.write(tempDirectory, yaml)));
    assertTrue(
        exception.getMessage().contains("anchor")
            || exception.getMessage().contains("alias")
            || exception.getMessage().contains("invalid YAML"));
  }

  @Test
  void rejectsExplicitNodeTags() throws Exception {
    String yaml = ConfigurationTestSupport.VALID.replace("schema: 1", "schema: !!int 1");
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml), "tags");
  }

  @Test
  void rejectsYamlDirectives() throws Exception {
    String yaml = "%YAML 1.2\n---\n" + ConfigurationTestSupport.VALID;
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml), "directives");
  }

  @Test
  void rejectsMultipleDocuments() throws Exception {
    String yaml = ConfigurationTestSupport.VALID + "\n---\n{}\n";
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml), "exactly one YAML document");
  }

  @Test
  void rejectsExcessiveNesting() throws Exception {
    StringBuilder yaml = new StringBuilder();
    for (int index = 0; index <= ConfigurationLimits.MAX_DEPTH; index++) {
      yaml.append("[");
    }
    yaml.append("0");
    for (int index = 0; index <= ConfigurationLimits.MAX_DEPTH; index++) {
      yaml.append("]");
    }
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml.toString()), "nesting depth");
  }

  @Test
  void rejectsOversizedScalar() throws Exception {
    String yaml = "key: " + "x".repeat(ConfigurationLimits.MAX_SCALAR_CODE_POINTS + 1);
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml), "scalar");
  }

  @Test
  void rejectsOversizedCollection() throws Exception {
    StringBuilder yaml = new StringBuilder("[\n");
    for (int index = 0; index <= ConfigurationLimits.MAX_COLLECTION_NODES; index++) {
      yaml.append("0,");
    }
    yaml.append("]\n");
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml.toString()), "collection");
  }

  @Test
  void rejectsExcessiveTotalNodeCount() throws Exception {
    StringBuilder yaml = new StringBuilder("[\n");
    for (int group = 0; group < 5; group++) {
      yaml.append("[");
      for (int index = 0; index < 3_300; index++) {
        yaml.append("0,");
      }
      yaml.append("],\n");
    }
    yaml.append("]\n");
    assertMessage(ConfigurationTestSupport.write(tempDirectory, yaml.toString()), "node count");
  }

  @Test
  void rejectsMalformedYaml() throws Exception {
    assertMessage(ConfigurationTestSupport.write(tempDirectory, "[unterminated"), "invalid YAML");
  }

  @Test
  void rejectsEmptyDocument() throws Exception {
    assertMessage(
        ConfigurationTestSupport.write(tempDirectory, ""),
        "configuration must contain exactly one complete YAML document");
  }

  private void assertMessage(Path path, String expected) {
    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> loader.load(path));
    assertTrue(
        exception.getMessage().contains(expected),
        () -> "expected <" + expected + "> in <" + exception.getMessage() + ">");
  }
}
