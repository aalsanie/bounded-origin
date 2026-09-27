package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.ScalarEvent;

public final class FixtureCatalog {
  private FixtureCatalog() {}

  public static void main(String[] args) throws IOException, ConfigurationException {
    if (args.length != 1) {
      throw new IllegalArgumentException("expected a new output directory");
    }
    Path directory = Files.createDirectory(Path.of(args[0]));
    List<String> inventory = new ArrayList<>();
    inventory.add("file,bytes,nodes,routes");
    for (int count : List.of(1, 16, 128, 256)) {
      for (var shape : BenchmarkFixtures.Shape.values()) {
        write(
            directory,
            "routes-" + count + "-" + shape,
            BenchmarkFixtures.yaml(count, shape, 1, false, false),
            inventory);
      }
      write(
          directory,
          "programmatic-" + count,
          BenchmarkFixtures.yaml(count, BenchmarkFixtures.Shape.FIXED, 1, true, true),
          inventory);
    }
    for (int dimensions : List.of(1, 8, 32, 128)) {
      for (boolean ordered : List.of(false, true)) {
        write(
            directory,
            "query-" + dimensions + "-ordered-" + ordered,
            BenchmarkFixtures.yaml(1, BenchmarkFixtures.Shape.CAPTURE, dimensions, ordered, false),
            inventory);
      }
    }
    for (int count : List.of(16, 128, 256)) {
      write(directory, "overlap-" + count, BenchmarkFixtures.overlappingYaml(count), inventory);
    }
    Files.write(
        directory.resolve("inventory.csv"),
        inventory,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    Files.writeString(
        directory.resolve("limits.properties"),
        "max-bytes="
            + ConfigurationLimits.MAX_BYTES
            + "\nmax-total-nodes="
            + ConfigurationLimits.MAX_TOTAL_NODES
            + "\nmax-routes="
            + ConfigurationLimits.MAX_ROUTES
            + "\nmax-dimensions="
            + ConfigurationLimits.MAX_DIMENSIONS
            + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
  }

  private static void write(Path directory, String name, String yaml, List<String> inventory)
      throws IOException, ConfigurationException {
    var loaded = BenchmarkFixtures.load(yaml);
    ConfiguredRuntime.validate(loaded);
    int nodes = 0;
    for (Event event : new Parse(LoadSettings.builder().build()).parseString(yaml)) {
      if (event instanceof ScalarEvent || event instanceof CollectionStartEvent) {
        nodes++;
      }
    }
    String filename = name + ".yaml";
    Files.writeString(
        directory.resolve(filename), yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    inventory.add(
        filename
            + ","
            + yaml.getBytes(StandardCharsets.UTF_8).length
            + ","
            + nodes
            + ","
            + loaded.routes().size());
  }
}
