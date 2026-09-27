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
public class OverlapEvaluationBenchmark {
  @Param({"16", "128", "256"})
  public int routeCount;

  private PolicyEngine engine;
  private RequestDescriptor request;

  @Setup
  public void setup() throws IOException, ConfigurationException {
    engine =
        BenchmarkFixtures.engine(
            BenchmarkFixtures.load(BenchmarkFixtures.overlappingYaml(routeCount)));
    request = BenchmarkFixtures.request("/route/0/item", "q0=value");
  }

  @Benchmark
  public OriginDecision evaluateOverlappingPrecedence() {
    return java.util.Objects.requireNonNull(engine).evaluate(request);
  }
}
