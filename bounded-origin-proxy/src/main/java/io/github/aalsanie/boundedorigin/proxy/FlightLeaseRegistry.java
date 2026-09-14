package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

final class FlightLeaseRegistry {
  private final Map<CompletionStage<Artifact>, Flight> flights = new IdentityHashMap<>();
  private final GatewayMetrics metrics;

  FlightLeaseRegistry(GatewayMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  Lease acquire(CompletionStage<Artifact> stage) {
    Objects.requireNonNull(stage, "stage");
    synchronized (flights) {
      Flight flight = flights.get(stage);
      if (flight == null) {
        flight = new Flight();
        flight.references = 1;
        flights.put(stage, flight);
        Flight captured = flight;
        stage.whenComplete((artifact, failure) -> complete(stage, captured, artifact));
      } else {
        flight.references++;
        metrics.singleFlightJoin();
      }
      return new Lease(this, stage);
    }
  }

  int trackedFlights() {
    synchronized (flights) {
      return flights.size();
    }
  }

  private void complete(CompletionStage<Artifact> stage, Flight expected, Artifact artifact) {
    Artifact cleanup = null;
    synchronized (flights) {
      Flight flight = flights.get(stage);
      if (flight != expected) {
        return;
      }
      flight.completed = true;
      flight.artifact = artifact;
      if (flight.references == 0) {
        flights.remove(stage);
        cleanup = artifact;
      }
    }
    deleteTemporary(cleanup);
  }

  private void release(CompletionStage<Artifact> stage) {
    Artifact cleanup = null;
    synchronized (flights) {
      Flight flight = flights.get(stage);
      if (flight == null) {
        return;
      }
      if (flight.references <= 0) {
        throw new IllegalStateException("flight reference count underflow");
      }
      flight.references--;
      if (flight.references == 0 && flight.completed) {
        flights.remove(stage);
        cleanup = flight.artifact;
      }
    }
    deleteTemporary(cleanup);
  }

  private static void deleteTemporary(Artifact artifact) {
    if (artifact == null || !(artifact.body() instanceof TemporaryArtifactBody temporary)) {
      return;
    }
    temporary.delete();
  }

  static final class Lease implements AutoCloseable {
    private final FlightLeaseRegistry owner;
    private final CompletionStage<Artifact> stage;
    private final AtomicBoolean released = new AtomicBoolean();

    private Lease(FlightLeaseRegistry owner, CompletionStage<Artifact> stage) {
      this.owner = owner;
      this.stage = stage;
    }

    @Override
    public void close() {
      if (released.compareAndSet(false, true)) {
        owner.release(stage);
      }
    }
  }

  private static final class Flight {
    private int references;
    private boolean completed;
    private Artifact artifact;
  }
}
