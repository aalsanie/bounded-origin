package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

class BenchmarkHarnessTest {
  @TempDir Path directory;

  @Test
  void installedHarnessExecutesEveryMethodAndProducesRawJson()
      throws java.io.IOException, InterruptedException {
    Path output = directory.resolve("smoke.json");
    Path log = directory.resolve("smoke.log");
    Process process =
        new ProcessBuilder(
                System.getProperty("benchmark.java"),
                "-cp",
                System.getProperty("benchmark.lib") + java.io.File.separator + "*",
                "org.openjdk.jmh.Main",
                "io.github.aalsanie.boundedorigin.cli.*Benchmark.*",
                "-f",
                "1",
                "-wi",
                "0",
                "-i",
                "1",
                "-r",
                "10ms",
                "-foe",
                "true",
                "-p",
                "routeCount=16",
                "-p",
                "shape=FIXED",
                "-p",
                "position=LAST",
                "-p",
                "queryDimensions=1",
                "-p",
                "queryCase=SELECTED",
                "-p",
                "ordered=false",
                "-rf",
                "json",
                "-rff",
                output.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), "JMH fixture smoke did not terminate");
      assertEquals(0, process.exitValue(), () -> read(log));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
    Object decoded = new Load(LoadSettings.builder().build()).loadFromString(read(output));
    List<?> results = org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, decoded);
    Set<Object> names = new HashSet<>();
    for (Object result : results) {
      Map<?, ?> row = org.junit.jupiter.api.Assertions.assertInstanceOf(Map.class, result);
      assertEquals(1, row.get("forks"));
      assertEquals(0, row.get("warmupIterations"));
      assertEquals(1, row.get("measurementIterations"));
      assertEquals("avgt", row.get("mode"));
      assertTrue(names.add(row.get("benchmark")));
      Map<?, ?> metric =
          org.junit.jupiter.api.Assertions.assertInstanceOf(Map.class, row.get("primaryMetric"));
      assertEquals("us/op", metric.get("scoreUnit"));
      List<?> forks =
          org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, metric.get("rawData"));
      assertEquals(1, forks.size());
      assertEquals(
          1,
          org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, forks.getFirst()).size());
    }
    assertEquals(12, names.size());
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }
}
