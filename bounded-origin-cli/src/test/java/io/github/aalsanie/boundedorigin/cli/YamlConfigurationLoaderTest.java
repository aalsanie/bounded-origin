package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigurationLoaderTest {
  @TempDir Path tempDirectory;

  private final YamlConfigurationLoader loader = new YamlConfigurationLoader();

  @Test
  void loadsTypedImmutableConfiguration() throws IOException, ConfigurationException {
    var configuration =
        loader.load(
            ConfigurationTestSupport.write(tempDirectory, ConfigurationTestSupport.validYaml()));

    assertEquals(1, configuration.schema());
    assertEquals("origin.internal", configuration.gateway().get("origin.host"));
    assertEquals("8080", configuration.gateway().get("origin.port"));
    assertEquals("false", configuration.gateway().get("forwarded.trust"));
    assertEquals("/var/lib/bounded-origin", configuration.store().directory());
    assertEquals(1_048_576L, configuration.store().maxBytes());
    assertEquals(262_144L, configuration.store().maxArtifactBytes());
    assertEquals(2, configuration.routes().size());

    var render = configuration.routes().getFirst();
    assertEquals("render", render.id());
    assertEquals(1L, render.version());
    assertEquals(100, render.precedence());
    assertEquals(ConfigurationModel.Strategy.MATERIALIZE, render.strategy());
    assertEquals("GET", render.match().method().orElseThrow());
    assertEquals("example.com", render.match().host().orElseThrow());
    assertEquals("/render/{id}", render.match().path());
    assertEquals(ConfigurationModel.Trust.UNTRUSTED, render.match().trust().orElseThrow());
    assertEquals(java.util.List.of("id"), render.key().orElseThrow().path());
    var query = render.key().orElseThrow().query().orElseThrow();
    assertEquals(java.util.List.of("variant"), query.include());
    assertTrue(query.orderIndependent());
    assertEquals("v1", render.materializerVersion().orElseThrow());
    assertEquals(4, render.budget().orElseThrow().maxActive());
    assertEquals(16, render.budget().orElseThrow().maxQueued());
    assertEquals(Duration.ofSeconds(10), render.budget().orElseThrow().maxExecutionDuration());
    assertEquals(262_144L, render.budget().orElseThrow().maxResultBytes());
    assertTrue(render.clientComputation().isEmpty());

    var client = configuration.routes().get(1);
    assertEquals(ConfigurationModel.Strategy.CLIENT_COMPUTE, client.strategy());
    assertTrue(client.match().method().isEmpty());
    assertTrue(client.match().host().isEmpty());
    assertTrue(client.match().trust().isEmpty());
    assertTrue(client.key().isEmpty());
    assertTrue(client.materializerVersion().isEmpty());
    assertTrue(client.budget().isEmpty());
    assertEquals("wasm", client.clientComputation().orElseThrow().type());
    assertEquals("v2", client.clientComputation().orElseThrow().version());
    assertEquals("strict", client.clientComputation().orElseThrow().parameters().get("mode"));

    assertEquals(ConfigurationModel.Strategy.DENY, configuration.fallback().strategy());
    assertThrows(UnsupportedOperationException.class, () -> configuration.gateway().put("x", "y"));
    assertThrows(UnsupportedOperationException.class, () -> configuration.routes().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> render.key().orElseThrow().path().add("other"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> client.clientComputation().orElseThrow().parameters().put("x", "y"));
  }

  @Test
  void supportsMinimalSafeConfiguration() throws IOException, ConfigurationException {
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
        routes: []
        fallback:
          id: deny
          version: 0
          precedence: 0
          strategy: DENY
        """;

    var configuration = loader.load(ConfigurationTestSupport.write(tempDirectory, yaml));

    assertTrue(configuration.routes().isEmpty());
    assertEquals(0L, configuration.store().maxArtifactBytes());
  }

  @Test
  void keepsEnvironmentSyntaxLiteral() throws IOException, ConfigurationException {
    String yaml =
        ConfigurationTestSupport.replace(
            ConfigurationTestSupport.validYaml(), "origin.internal", "${ORIGIN_HOST}");

    var configuration = loader.load(ConfigurationTestSupport.write(tempDirectory, yaml));

    assertEquals("${ORIGIN_HOST}", configuration.gateway().get("origin.host"));
  }

  @Test
  void preservesIoFailures() {
    Path missing = tempDirectory.resolve("missing.yaml");
    assertThrows(IOException.class, () -> loader.load(missing));
  }

  @Test
  void rejectsInvalidUtf8() throws IOException {
    Path path = tempDirectory.resolve("config.yaml");
    Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28});

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> loader.load(path));

    assertTrue(exception.getMessage().contains("UTF-8"));
    assertFalse(exception.getCause() == null);
  }
}
