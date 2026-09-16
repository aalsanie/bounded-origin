package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.schema.JsonSchema;

final class YamlConfigurationLoader {
  private static final LoadSettings SETTINGS =
      LoadSettings.builder()
          .setLabel("bounded-origin configuration")
          .setAllowDuplicateKeys(false)
          .setAllowRecursiveKeys(false)
          .setAllowNonScalarKeys(false)
          .setMaxAliasesForCollections(0)
          .setCodePointLimit(ConfigurationLimits.MAX_CODE_POINTS)
          .setParseComments(false)
          .setUseMarks(false)
          .setSchema(new JsonSchema())
          .build();

  ConfigurationModel.RuntimeConfiguration load(Path path)
      throws IOException, ConfigurationException {
    Objects.requireNonNull(path, "path");
    String yaml = decode(readBounded(path));
    YamlStructureGuard.validate(yaml, SETTINGS);
    Object root;
    try {
      root = new Load(SETTINGS).loadFromString(yaml);
    } catch (YamlEngineException exception) {
      throw new ConfigurationException("invalid YAML configuration", exception);
    }
    return ConfigurationDecoder.decode(root);
  }

  private static byte[] readBounded(Path path) throws IOException, ConfigurationException {
    try (InputStream input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(ConfigurationLimits.MAX_BYTES + 1);
      if (bytes.length > ConfigurationLimits.MAX_BYTES) {
        throw new ConfigurationException("configuration exceeds maximum size");
      }
      return bytes;
    }
  }

  private static String decode(byte[] bytes) throws ConfigurationException {
    try {
      CharBuffer decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes));
      return decoded.toString();
    } catch (CharacterCodingException exception) {
      throw new ConfigurationException("configuration must be valid UTF-8", exception);
    }
  }
}
