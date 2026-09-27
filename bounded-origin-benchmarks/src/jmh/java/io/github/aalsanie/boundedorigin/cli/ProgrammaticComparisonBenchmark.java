package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import java.io.IOException;
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
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(10)
public class ProgrammaticComparisonBenchmark {
  @Param({"1", "16", "128", "256"})
  public int routeCount;

  @Param({"FIRST", "LAST", "MISS"})
  public String position;

  private PolicyEngine configured;
  private PolicyEngine programmatic;
  private RequestDescriptor request;

  @Setup
  public void setup() throws IOException, ConfigurationException {
    configured =
        BenchmarkFixtures.engine(
            BenchmarkFixtures.load(
                BenchmarkFixtures.yaml(routeCount, BenchmarkFixtures.Shape.FIXED, 1, true, true)));
    programmatic = ProgrammaticPolicies.fixedPaths(routeCount);
    request =
        BenchmarkFixtures.request(
            BenchmarkFixtures.path(
                routeCount,
                BenchmarkFixtures.Shape.FIXED,
                BenchmarkFixtures.Position.valueOf(position)),
            "q0=value&q0=second&noise=ignored");
  }

  @Benchmark
  public OriginDecision configured() {
    return java.util.Objects.requireNonNull(configured).evaluate(request);
  }

  @Benchmark
  public OriginDecision programmatic() {
    return java.util.Objects.requireNonNull(programmatic).evaluate(request);
  }
}
