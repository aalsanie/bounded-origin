package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

final class ExecutionTestSupport {
  private ExecutionTestSupport() {}

  static Artifact artifact(long contentLength) {
    return new Artifact(contentLength, Map.of(), InputStream::nullInputStream);
  }

  static OriginPolicy policy(String id, Budget budget) {
    return OriginPolicy.boundedCompute(
        id, 1, 1, "materializer-1", Canonicalizers.byDimensions(), budget);
  }

  static OriginPolicy materializePolicy(String id, Budget budget) {
    return OriginPolicy.materialize(
        id, 1, 1, "materializer-1", Canonicalizers.byDimensions(), budget);
  }

  static OriginDecision.Selected decision(OriginPolicy policy, String identity) {
    return new OriginDecision.Selected(
        policy,
        new Operation("test", Map.of()),
        new OperationKey(
            policy.id(), policy.version(), identity, policy.materializerVersion().orElseThrow()));
  }

  static Artifact join(CompletionStage<Artifact> stage) {
    return stage.toCompletableFuture().join();
  }

  static OriginExecutionException failure(CompletionStage<Artifact> stage) {
    try {
      join(stage);
      throw new AssertionError("expected exceptional completion");
    } catch (CompletionException exception) {
      return assertInstanceOf(OriginExecutionException.class, exception.getCause());
    }
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }
}
