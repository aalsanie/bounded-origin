package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.core.OriginExecution;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

final class FlightLeaseRegistry {
  private final Map<CompletionStage<Artifact>, Flight> flights = new IdentityHashMap<>();
  private final GatewayMetrics metrics;

  FlightLeaseRegistry(GatewayMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  Lease acquire(OriginExecution execution) {
    Objects.requireNonNull(execution, "execution");
    if (execution.joined()) {
      metrics.singleFlightJoin();
    }
    return track(execution.result(), execution::close);
  }

  Lease acquire(Artifact artifact) {
    Objects.requireNonNull(artifact, "artifact");
    return track(CompletableFuture.completedFuture(artifact), () -> closeBody(artifact));
  }

  private Lease track(CompletionStage<Artifact> stage, Runnable release) {
    synchronized (flights) {
      Flight flight = flights.get(stage);
      if (flight == null) {
        flight = new Flight();
        flight.references = 1;
        flights.put(stage, flight);
        Flight captured = flight;
        stage.whenComplete((artifact, failure) -> complete(stage, captured));
      } else {
        flight.references++;
      }
      return new Lease(this, stage, release);
    }
  }

  int trackedFlights() {
    synchronized (flights) {
      return flights.size();
    }
  }

  private void complete(CompletionStage<Artifact> stage, Flight expected) {
    synchronized (flights) {
      Flight flight = flights.get(stage);
      if (flight != expected) {
        return;
      }
      flight.completed = true;
      if (flight.references == 0) {
        flights.remove(stage);
      }
    }
  }

  private void release(CompletionStage<Artifact> stage) {
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
      }
    }
  }

  private static void closeBody(Artifact artifact) {
    try {
      artifact.body().close();
    } catch (IOException | RuntimeException exception) {
      System.getLogger(FlightLeaseRegistry.class.getName())
          .log(System.Logger.Level.WARNING, "failed to release stored result", exception);
    }
  }

  static final class Lease implements AutoCloseable {
    private final FlightLeaseRegistry owner;
    private final CompletionStage<Artifact> stage;
    private final Runnable release;
    private final AtomicBoolean released = new AtomicBoolean();

    private Lease(FlightLeaseRegistry owner, CompletionStage<Artifact> stage, Runnable release) {
      this.owner = owner;
      this.stage = stage;
      this.release = release;
    }

    CompletionStage<Artifact> result() {
      return stage;
    }

    @Override
    public void close() {
      if (released.compareAndSet(false, true)) {
        try {
          release.run();
        } finally {
          owner.release(stage);
        }
      }
    }
  }

  private static final class Flight {
    private int references;
    private boolean completed;
  }
}
