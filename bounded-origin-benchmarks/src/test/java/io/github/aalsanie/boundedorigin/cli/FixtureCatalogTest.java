package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureCatalogTest {
  @TempDir Path directory;

  @Test
  void catalogRetainsEveryExactConfigurationAndItsLimits()
      throws IOException, ConfigurationException {
    Path output = directory.resolve("catalog");
    FixtureCatalog.main(new String[] {output.toString()});
    var inventory = Files.readAllLines(output.resolve("inventory.csv"), StandardCharsets.UTF_8);
    assertEquals(28, inventory.size());
    assertEquals("file,bytes,nodes,routes", inventory.getFirst());
    for (String row : inventory.subList(1, inventory.size())) {
      String[] fields = row.split(",");
      Path file = output.resolve(fields[0]);
      assertEquals(Files.size(file), Long.parseLong(fields[1]));
      int nodes = Integer.parseInt(fields[2]);
      assertTrue(nodes > 0 && nodes <= ConfigurationLimits.MAX_TOTAL_NODES);
      var loaded = new YamlConfigurationLoader().load(file);
      ConfiguredRuntime.validate(loaded);
      assertEquals(loaded.routes().size(), Integer.parseInt(fields[3]));
    }
    Properties limits = new Properties();
    try (Reader reader =
        Files.newBufferedReader(output.resolve("limits.properties"), StandardCharsets.UTF_8)) {
      limits.load(reader);
    }
    assertEquals(
        Integer.toString(ConfigurationLimits.MAX_ROUTES), limits.getProperty("max-routes"));
    assertEquals(Integer.toString(ConfigurationLimits.MAX_BYTES), limits.getProperty("max-bytes"));
    assertEquals(
        Integer.toString(ConfigurationLimits.MAX_TOTAL_NODES),
        limits.getProperty("max-total-nodes"));
    assertEquals(
        Integer.toString(ConfigurationLimits.MAX_DIMENSIONS), limits.getProperty("max-dimensions"));
    assertThrows(
        FileAlreadyExistsException.class,
        () -> FixtureCatalog.main(new String[] {output.toString()}));
    assertEquals(
        inventory, Files.readAllLines(output.resolve("inventory.csv"), StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> FixtureCatalog.main(new String[0]));
    assertThrows(
        IllegalArgumentException.class, () -> FixtureCatalog.main(new String[] {"a", "b"}));
  }
}
