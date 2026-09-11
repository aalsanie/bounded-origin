package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.MaterializationException;
import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class GatewayRequestProcessor {
  private final GatewayConfig config;
  private final PolicyEngine policyEngine;
  private final ArtifactStore artifactStore;
  private final BoundedOriginExecutor executor;
  private final NettyOriginClient originClient;
  private final GatewayMetrics metrics;
  private final FlightLeaseRegistry flights;

  GatewayRequestProcessor(
      GatewayConfig config,
      PolicyEngine policyEngine,
      ArtifactStore artifactStore,
      BoundedOriginExecutor executor,
      NettyOriginClient originClient,
      GatewayMetrics metrics,
      FlightLeaseRegistry flights) {
    this.config = Objects.requireNonNull(config, "config");
    this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.originClient = Objects.requireNonNull(originClient, "originClient");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.flights = Objects.requireNonNull(flights, "flights");
  }

  Outcome process(GatewayRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      RequestDescriptor descriptor =
          HttpRequestSecurity.descriptor(
              request.validated(), request.body(), config.ingressTrustLevel());
      OriginDecision decision = policyEngine.evaluate(descriptor);
      if (decision instanceof OriginDecision.Denied denied) {
        deleteRequestBody(request.body());
        int status =
            switch (denied.reason()) {
              case POLICY_DENIED, NO_MATCH -> 403;
              case AMBIGUOUS_POLICY, POLICY_ERROR -> 500;
            };
        return immediate(
            ResponseArtifacts.text(
                status, status == 403 ? "request denied\n" : "policy evaluation failed\n"),
            String.join(",", denied.policyIds()));
      }

      OriginDecision.Selected selected = (OriginDecision.Selected) decision;
      OriginPolicy policy = selected.policy();
      return switch (policy.strategy()) {
        case ARTIFACT_ONLY -> artifactOnly(request, selected);
        case BOUNDED_COMPUTE -> bounded(request, selected, false);
        case MATERIALIZE -> bounded(request, selected, true);
        case CLIENT_COMPUTE -> clientCompute(request, selected);
        case DENY ->
            throw new IllegalStateException("PolicyEngine returned a selected DENY policy");
      };
    } catch (RuntimeException exception) {
      deleteRequestBody(request.body());
      throw exception;
    }
  }

  private Outcome artifactOnly(GatewayRequest request, OriginDecision.Selected selected) {
    Optional<Artifact> stored = getStored(selected);
    deleteRequestBody(request.body());
    if (stored.isPresent()) {
      metrics.artifactHit();
      return immediate(stored.orElseThrow(), selected.policy().id());
    }
    metrics.artifactMiss();
    return immediate(
        ResponseArtifacts.text(404, "artifact not available\n"), selected.policy().id());
  }

  private Outcome bounded(
      GatewayRequest request, OriginDecision.Selected selected, boolean persist) {
    Optional<Artifact> stored = getStored(selected);
    if (stored.isPresent()) {
      metrics.artifactHit();
      deleteRequestBody(request.body());
      return immediate(stored.orElseThrow(), selected.policy().id());
    }
    metrics.artifactMiss();

    CompletionStage<Artifact> stage;
    try {
      stage =
          executor.execute(
              selected,
              ignored -> {
                metrics.originExecution();
                Artifact generated = executeOrigin(request, selected);
                if (!persist) {
                  return generated;
                }
                long materializationStarted = System.nanoTime();
                try {
                  artifactStore.put(selected.operationKey(), generated);
                  metrics.bytesStored(generated.contentLength());
                  return artifactStore
                      .get(selected.operationKey())
                      .orElseThrow(
                          () ->
                              new MaterializationException("persisted artifact was not readable"));
                } catch (IOException exception) {
                  metrics.storeFailure();
                  throw new MaterializationException(
                      "failed to persist materialized artifact", exception);
                } finally {
                  metrics.materializationDuration(System.nanoTime() - materializationStarted);
                  deleteTemporaryArtifact(generated);
                }
              });
    } catch (RuntimeException exception) {
      deleteRequestBody(request.body());
      throw exception;
    }

    FlightLeaseRegistry.Lease lease = flights.acquire(stage);
    stage.whenComplete((artifact, failure) -> deleteRequestBody(request.body()));
    return new Outcome(stage, lease, selected.policy().id());
  }

  private Outcome clientCompute(GatewayRequest request, OriginDecision.Selected selected) {
    metrics.clientComputeDecision();
    deleteRequestBody(request.body());
    return immediate(
        ResponseArtifacts.clientComputation(selected.policy().clientComputation().orElseThrow()),
        selected.policy().id());
  }

  private Artifact executeOrigin(GatewayRequest request, OriginDecision.Selected selected)
      throws MaterializationException {
    long policyLimit = selected.policy().budget().orElseThrow().maxResultBytes();
    long maxResponseBytes = Math.min(config.globalBudget().maxResultBytes(), policyLimit);
    OriginRequest originRequest =
        new OriginRequest(
            request.validated().method(),
            request.validated().target(),
            request.originHeaders(),
            request.body(),
            maxResponseBytes);
    return originClient.execute(originRequest);
  }

  private Optional<Artifact> getStored(OriginDecision.Selected selected) {
    try {
      return artifactStore.get(selected.operationKey());
    } catch (IOException exception) {
      metrics.storeFailure();
      throw new GatewayStoreException(exception);
    }
  }

  private static Outcome immediate(Artifact artifact, String policyId) {
    return new Outcome(CompletableFuture.completedFuture(artifact), null, policyId);
  }

  private static void deleteTemporaryArtifact(Artifact artifact) {
    if (!(artifact.body() instanceof TemporaryArtifactBody body)) {
      return;
    }
    body.delete();
  }

  private static void deleteRequestBody(StreamingSpool.Result body) {
    body.close();
  }

  record Outcome(
      CompletionStage<Artifact> artifact, FlightLeaseRegistry.Lease flightLease, String policyId) {
    Outcome {
      artifact = Objects.requireNonNull(artifact, "artifact");
      policyId = Objects.requireNonNull(policyId, "policyId");
    }
  }

  static final class GatewayStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GatewayStoreException(IOException cause) {
      super("artifact store operation failed", cause);
    }
  }
}
