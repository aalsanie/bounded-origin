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
public class RouteEvaluationBenchmark {
  @Param({"16", "128", "256"})
  public int routeCount;

  @Param({"FIXED", "CAPTURE", "CATCH_ALL"})
  public String shape;

  @Param({"FIRST", "MIDDLE", "LAST", "MISS"})
  public String position;

  private PolicyEngine engine;
  private RequestDescriptor request;

  @Setup
  public void setup() throws IOException, ConfigurationException {
    var pathShape = BenchmarkFixtures.Shape.valueOf(shape);
    engine =
        BenchmarkFixtures.engine(
            BenchmarkFixtures.load(BenchmarkFixtures.yaml(routeCount, pathShape, 1, false, false)));
    request =
        BenchmarkFixtures.request(
            BenchmarkFixtures.path(
                routeCount, pathShape, BenchmarkFixtures.Position.valueOf(position)),
            "q0=value");
  }

  @Benchmark
  public OriginDecision evaluate() {
    return java.util.Objects.requireNonNull(engine).evaluate(request);
  }
}
