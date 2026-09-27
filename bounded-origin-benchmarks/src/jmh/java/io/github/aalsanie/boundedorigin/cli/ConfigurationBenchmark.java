package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Budget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(10)
public class ConfigurationBenchmark {
  @Param({"1", "16", "128", "256"})
  public int routeCount;

  private Path file;
  private ConfigurationModel.RuntimeConfiguration configuration;
  private Budget budget;

  @Setup
  public void setup() throws IOException, ConfigurationException {
    file = Files.createTempFile("bounded-origin-jmh-", ".yaml");
    Files.writeString(
        file,
        BenchmarkFixtures.yaml(routeCount, BenchmarkFixtures.Shape.CAPTURE, 1, false, false),
        StandardCharsets.UTF_8);
    configuration = new YamlConfigurationLoader().load(file);
    ConfiguredRuntime.validate(configuration);
    budget = BenchmarkFixtures.budget(configuration);
  }

  @TearDown
  public void cleanup() throws IOException {
    Files.delete(file);
  }

  @Benchmark
  public Object boundedYamlLoadAndDecode() throws IOException, ConfigurationException {
    return new YamlConfigurationLoader().load(file);
  }

  @Benchmark
  public Object gatewayFieldValidation() throws ConfigurationException {
    GatewayConfigurationValidator.validate(
        java.util.Objects.requireNonNull(configuration).gateway());
    return java.util.Objects.requireNonNull(configuration).gateway();
  }

  @Benchmark
  public Object routeTemplateCompilation() throws ConfigurationException {
    return RouteTemplateCompiler.compile(java.util.Objects.requireNonNull(configuration).routes());
  }

  @Benchmark
  public Object policyCompilation() throws ConfigurationException {
    return PolicyConfigurationCompiler.compile(configuration, budget);
  }

  @Benchmark
  public Object semanticValidationAndCompilation() throws ConfigurationException {
    ConfiguredRuntime.validate(configuration);
    return configuration;
  }

  @Benchmark
  public Object completeLoadAndCompile() throws IOException, ConfigurationException {
    var loaded = new YamlConfigurationLoader().load(file);
    ConfiguredRuntime.validate(loaded);
    return loaded;
  }
}
