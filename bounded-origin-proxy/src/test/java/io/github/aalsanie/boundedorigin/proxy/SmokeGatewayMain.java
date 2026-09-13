package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

final class SmokeGatewayMain {
  private SmokeGatewayMain() {}

  public static void main(String[] args) throws Exception {
    Budget budget = new Budget(2, 4, Duration.ofSeconds(10), 1024 * 1024);
    OriginPolicy policy =
        OriginPolicy.boundedCompute(
            "smoke", 1, 100, "v1", Canonicalizers.byDimensions("path"), budget);
    PolicyRule rule =
        new PolicyRule(
            policy,
            request -> {
              List<String> paths = request.attributes().get("path");
              if (paths == null || paths.size() != 1 || !"/smoke".equals(paths.getFirst())) {
                return Optional.empty();
              }
              return Optional.of(new Operation("http", Map.of("path", List.of("/smoke"))));
            });
    PolicyEngine engine = new PolicyEngine(List.of(rule), OriginPolicy.deny("fallback", 1, 0));
    GatewayConfig config =
        GatewayConfig.defaults(
            new InetSocketAddress("0.0.0.0", 8080),
            new InetSocketAddress("0.0.0.0", 8081),
            new InetSocketAddress("origin", 9000),
            Path.of(System.getProperty("java.io.tmpdir"), "bounded-origin"),
            budget);

    BoundedOriginGateway gateway =
        new BoundedOriginGateway(config, engine, new EmptyArtifactStore());
    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(gateway::close));
    gateway.start();
    new CountDownLatch(1).await();
  }

  private static final class EmptyArtifactStore implements ArtifactStore {
    @Override
    public Optional<Artifact> get(OperationKey key) {
      return Optional.empty();
    }

    @Override
    public void put(OperationKey key, Artifact artifact) throws IOException {
      throw new IOException("smoke policy does not materialize artifacts");
    }
  }
}
