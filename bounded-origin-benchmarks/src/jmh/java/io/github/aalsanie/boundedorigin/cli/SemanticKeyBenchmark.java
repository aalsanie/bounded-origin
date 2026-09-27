package io.github.aalsanie.boundedorigin.cli;

import io.github.aalsanie.boundedorigin.api.Canonicalizer;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
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
public class SemanticKeyBenchmark {
  @Param({"1", "8", "32", "128"})
  public int queryDimensions;

  @Param({"SELECTED", "NOISE", "DUPLICATES", "MISSING", "BARE", "EMPTY", "REVERSED", "ENCODED"})
  public String queryCase;

  @Param({"false", "true"})
  public boolean ordered;

  private SemanticKeyPlan plan;
  private Canonicalizer canonicalizer;
  private RequestDescriptor request;
  private Map<String, String> captures;
  private Operation projected;

  @Setup
  public void setup() throws IOException, ConfigurationException {
    var route =
        BenchmarkFixtures.load(
                BenchmarkFixtures.yaml(
                    1, BenchmarkFixtures.Shape.CAPTURE, queryDimensions, ordered, false))
            .routes()
            .getFirst();
    plan = SemanticKeyPlan.compile(route, Set.of("id"), "benchmark.route");
    canonicalizer = plan.canonicalizer();
    var variant = BenchmarkFixtures.QueryCase.valueOf(queryCase);
    String capture = variant == BenchmarkFixtures.QueryCase.ENCODED ? "%69tem" : "item";
    captures = Map.of("id", capture);
    request =
        BenchmarkFixtures.request(
            "/route/0/" + capture, BenchmarkFixtures.query(queryDimensions, variant));
    projected = plan.operation(request, captures);
  }

  @Benchmark
  public String projectAndCanonicalize() {
    return java.util.Objects.requireNonNull(canonicalizer)
        .canonicalize(java.util.Objects.requireNonNull(plan).operation(request, captures));
  }

  @Benchmark
  public String canonicalizeProjectedOperation() {
    return java.util.Objects.requireNonNull(canonicalizer).canonicalize(projected);
  }
}
