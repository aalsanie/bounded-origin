package io.github.aalsanie.boundedorigin.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.Materializer;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PolicyModelIntegrationTest {
  @Test
  void inMemoryOriginUsesSemanticKeyWithoutHttp() throws IOException, MaterializationException {
    OriginPolicy policy =
        OriginPolicy.materialize(
            "render",
            4,
            100,
            "renderer-9",
            Canonicalizers.byDimensions("id", "format"),
            new Budget(1, 2, Duration.ofSeconds(1), 4096));
    PolicyRule rule =
        new PolicyRule(
            policy,
            request ->
                request.name().equals("render")
                    ? Optional.of(new Operation("render", request.attributes()))
                    : Optional.empty());
    PolicyEngine engine = new PolicyEngine(List.of(rule), OriginPolicy.deny("fallback", 1, -1));
    InMemoryStore store = new InMemoryStore();
    AtomicInteger executions = new AtomicInteger();
    Materializer materializer =
        operation -> {
          executions.incrementAndGet();
          byte[] bytes =
              (operation.dimensions().get("id").getFirst()
                      + ":"
                      + operation.dimensions().get("format").getFirst())
                  .getBytes(StandardCharsets.UTF_8);
          return new Artifact(
              bytes.length, Map.of("kind", "rendered"), () -> new ByteArrayInputStream(bytes));
        };

    Map<String, List<String>> firstAttributes = new LinkedHashMap<>();
    firstAttributes.put("id", List.of("42"));
    firstAttributes.put("format", List.of("webp"));
    Map<String, List<String>> secondAttributes = new LinkedHashMap<>();
    secondAttributes.put("format", List.of("webp"));
    secondAttributes.put("id", List.of("42"));

    OriginDecision.Selected first =
        assertInstanceOf(
            OriginDecision.Selected.class,
            engine.evaluate(
                new RequestDescriptor("render", firstAttributes, TrustLevel.UNTRUSTED)));
    OriginDecision.Selected second =
        assertInstanceOf(
            OriginDecision.Selected.class,
            engine.evaluate(
                new RequestDescriptor("render", secondAttributes, TrustLevel.UNTRUSTED)));

    assertEquals(first.operationKey(), second.operationKey());
    Artifact firstArtifact = getOrMaterialize(store, materializer, first);
    Artifact secondArtifact = getOrMaterialize(store, materializer, second);

    assertEquals(1, executions.get());
    assertEquals(1, store.size());
    assertArrayEquals(
        firstArtifact.body().openStream().readAllBytes(),
        secondArtifact.body().openStream().readAllBytes());
    assertEquals("kind", firstArtifact.metadata().keySet().iterator().next());
    assertTrue(first.operationKey().semanticIdentity().contains("render"));
  }

  private static Artifact getOrMaterialize(
      ArtifactStore store, Materializer materializer, OriginDecision.Selected decision)
      throws IOException, MaterializationException {
    Optional<Artifact> existing = store.get(decision.operationKey());
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    Artifact artifact = materializer.materialize(decision.operation());
    store.put(decision.operationKey(), artifact);
    return artifact;
  }

  private static final class InMemoryStore implements ArtifactStore {
    private final Map<OperationKey, Artifact> artifacts = new HashMap<>();

    @Override
    public Optional<Artifact> get(OperationKey key) {
      return Optional.ofNullable(artifacts.get(key));
    }

    @Override
    public void put(OperationKey key, Artifact artifact) throws IOException {
      if (artifacts.putIfAbsent(key, artifact) != null) {
        throw new IOException("duplicate artifact");
      }
    }

    int size() {
      return artifacts.size();
    }
  }
}
