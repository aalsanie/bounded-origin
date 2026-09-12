package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.ClientComputation;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class GatewayTestFixtures {
  private static final List<String> KEY_DIMENSIONS =
      List.of("method", "host", "path", "query", "body-sha256");

  private GatewayTestFixtures() {}

  static GatewayConfig config(int originPort, Path temporaryDirectory) {
    return config(originPort, temporaryDirectory, Map.of());
  }

  static GatewayConfig config(
      int originPort, Path temporaryDirectory, Map<String, String> overrides) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("listen.host", "127.0.0.1");
    values.put("listen.port", "0");
    values.put("admin.host", "127.0.0.1");
    values.put("admin.port", "0");
    values.put("origin.host", "127.0.0.1");
    values.put("origin.port", Integer.toString(originPort));
    values.put("temporary.directory", temporaryDirectory.toString());
    values.put("event-loop.threads", "2");
    values.put("origin.event-loop.threads", "2");
    values.put("frontend.max-connections", "64");
    values.put("server.backlog", "64");
    values.put("http.max-initial-line-bytes", "4096");
    values.put("http.max-header-bytes", "8192");
    values.put("http.chunk-bytes", "1024");
    values.put("http.max-request-body-bytes", "1048576");
    values.put("http.chunked-response-threshold-bytes", "32");
    values.put("spool.max-bytes", "8388608");
    values.put("spool.max-files", "128");
    values.put("origin.max-connections", "4");
    values.put("origin.max-pending-acquires", "8");
    values.put("origin.acquire-timeout", "PT0.5S");
    values.put("origin.max-active", "4");
    values.put("origin.max-queued", "8");
    values.put("origin.max-execution-duration", "PT5S");
    values.put("origin.max-result-bytes", "1048576");
    values.put("origin.connect-timeout", "PT0.5S");
    values.put("origin.response-timeout", "PT4S");
    values.put("origin.failure-cooldown", "PT0.2S");
    values.put("origin.max-cooldown-entries", "128");
    values.put("request.timeout", "PT6S");
    values.put("idle.timeout", "PT3S");
    values.put("drain.timeout", "PT2S");
    values.put("overload.retry-after-seconds", "1");
    values.put("downstream.write-low-watermark-bytes", "1024");
    values.put("downstream.write-high-watermark-bytes", "2048");
    values.putAll(overrides);
    return GatewayConfig.from(values);
  }

  static Budget budget(GatewayConfig config) {
    return config.globalBudget();
  }

  static OriginPolicy boundedPolicy(Budget budget) {
    return OriginPolicy.boundedCompute(
        "bounded", 1, 100, "origin-v1", Canonicalizers.byDimensions(KEY_DIMENSIONS), budget);
  }

  static OriginPolicy materializePolicy(Budget budget) {
    return OriginPolicy.materialize(
        "materialize", 1, 100, "origin-v1", Canonicalizers.byDimensions(KEY_DIMENSIONS), budget);
  }

  static OriginPolicy artifactOnlyPolicy() {
    return OriginPolicy.artifactOnly(
        "artifact", 1, 100, "origin-v1", Canonicalizers.byDimensions(KEY_DIMENSIONS));
  }

  static OriginPolicy clientComputePolicy() {
    return OriginPolicy.clientCompute(
        "client",
        1,
        100,
        "client-v1",
        Canonicalizers.byDimensions(KEY_DIMENSIONS),
        new ClientComputation("sha256", "1", Map.of("input", "body")));
  }

  static PolicyEngine engine(OriginPolicy policy) {
    return new PolicyEngine(
        List.of(new PolicyRule(policy, request -> Optional.of(operation(request)))), fallback());
  }

  static PolicyEngine routeEngine(Map<String, OriginPolicy> policies) {
    List<PolicyRule> rules = new ArrayList<>();
    policies.forEach(
        (path, policy) ->
            rules.add(
                new PolicyRule(
                    policy,
                    request ->
                        path.equals(path(request))
                            ? Optional.of(operation(request))
                            : Optional.empty())));
    return new PolicyEngine(rules, fallback());
  }

  static BoundedOriginGateway start(GatewayConfig config, PolicyEngine engine, ArtifactStore store)
      throws IOException {
    BoundedOriginGateway gateway = new BoundedOriginGateway(config, engine, store);
    gateway.start();
    return gateway;
  }

  static int unusedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  static Artifact artifact(int status, String body) {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return new Artifact(
        status,
        bytes.length,
        Map.of("content-type", "text/plain"),
        () -> new ByteArrayInputStream(bytes));
  }

  static String path(RequestDescriptor request) {
    List<String> values = request.attributes().get("path");
    return values == null ? "" : values.getFirst();
  }

  private static Operation operation(RequestDescriptor request) {
    return new Operation("http.request", request.attributes());
  }

  private static OriginPolicy fallback() {
    return OriginPolicy.deny("fallback", 1, Integer.MIN_VALUE);
  }

  static final class MemoryArtifactStore implements ArtifactStore {
    private final Map<OperationKey, StoredArtifact> artifacts = new ConcurrentHashMap<>();
    private volatile StoredArtifact defaultArtifact;
    private volatile boolean failReads;
    private volatile boolean failWrites;

    void defaultArtifact(Artifact artifact) throws IOException {
      defaultArtifact = StoredArtifact.copyOf(artifact);
    }

    void clearDefaultArtifact() {
      defaultArtifact = null;
    }

    void failReads(boolean value) {
      failReads = value;
    }

    void failWrites(boolean value) {
      failWrites = value;
    }

    int size() {
      return artifacts.size();
    }

    @Override
    public Optional<Artifact> get(OperationKey key) throws IOException {
      if (failReads) {
        throw new IOException("read failure");
      }
      StoredArtifact stored = artifacts.get(key);
      if (stored == null) {
        stored = defaultArtifact;
      }
      return stored == null ? Optional.empty() : Optional.of(stored.artifact());
    }

    @Override
    public void put(OperationKey key, Artifact artifact) throws IOException {
      if (failWrites) {
        throw new IOException("write failure");
      }
      artifacts.put(key, StoredArtifact.copyOf(artifact));
    }
  }

  private record StoredArtifact(int status, Map<String, String> metadata, byte[] body) {
    static StoredArtifact copyOf(Artifact artifact) throws IOException {
      byte[] bytes;
      try (InputStream input = artifact.body().openStream()) {
        if (artifact.contentLength() > Integer.MAX_VALUE) {
          throw new IOException("test artifact is too large");
        }
        bytes = input.readNBytes((int) artifact.contentLength());
        if (bytes.length != artifact.contentLength() || input.read() >= 0) {
          throw new IOException("artifact length mismatch");
        }
      }
      return new StoredArtifact(artifact.statusCode(), artifact.metadata(), bytes);
    }

    Artifact artifact() {
      byte[] copy = body.clone();
      return new Artifact(status, copy.length, metadata, () -> new ByteArrayInputStream(copy));
    }
  }
}
