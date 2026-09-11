package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import java.util.concurrent.atomic.LongAdder;

final class GatewayMetrics {
  private final LongAdder requests = new LongAdder();
  private final LongAdder artifactHits = new LongAdder();
  private final LongAdder artifactMisses = new LongAdder();
  private final LongAdder originExecutions = new LongAdder();
  private final LongAdder singleFlightJoins = new LongAdder();
  private final LongAdder rejections = new LongAdder();
  private final LongAdder originDurationNanos = new LongAdder();
  private final LongAdder originDurationCount = new LongAdder();
  private final LongAdder materializationDurationNanos = new LongAdder();
  private final LongAdder materializationDurationCount = new LongAdder();
  private final LongAdder bytesServed = new LongAdder();
  private final LongAdder bytesStored = new LongAdder();
  private final LongAdder clientComputeDecisions = new LongAdder();
  private final LongAdder requestBodyBytes = new LongAdder();
  private final LongAdder originResponseBytes = new LongAdder();
  private final LongAdder malformedRequests = new LongAdder();
  private final LongAdder storeFailures = new LongAdder();
  private final LongAdder originPoolRejections = new LongAdder();

  void request() {
    requests.increment();
  }

  void artifactHit() {
    artifactHits.increment();
  }

  void artifactMiss() {
    artifactMisses.increment();
  }

  void originExecution() {
    originExecutions.increment();
  }

  void singleFlightJoin() {
    singleFlightJoins.increment();
  }

  void rejection() {
    rejections.increment();
  }

  void originDuration(long nanos) {
    originDurationNanos.add(nanos);
    originDurationCount.increment();
  }

  void materializationDuration(long nanos) {
    materializationDurationNanos.add(nanos);
    materializationDurationCount.increment();
  }

  void bytesServed(long bytes) {
    bytesServed.add(bytes);
  }

  void bytesStored(long bytes) {
    bytesStored.add(bytes);
  }

  void clientComputeDecision() {
    clientComputeDecisions.increment();
  }

  void requestBodyBytes(long bytes) {
    requestBodyBytes.add(bytes);
  }

  void originResponseBytes(long bytes) {
    originResponseBytes.add(bytes);
  }

  void malformedRequest() {
    malformedRequests.increment();
  }

  void storeFailure() {
    storeFailures.increment();
  }

  void originPoolRejection() {
    originPoolRejections.increment();
  }

  String prometheus(
      OriginExecutorStats executorStats,
      int clientConnections,
      int activeRequests,
      int originConnections,
      int originPendingAcquires,
      long spoolBytes,
      int spoolFiles) {
    StringBuilder output = new StringBuilder(1_024);
    counter(output, "bounded_origin_requests_total", requests.sum());
    counter(output, "bounded_origin_artifact_hits_total", artifactHits.sum());
    counter(output, "bounded_origin_artifact_misses_total", artifactMisses.sum());
    counter(output, "bounded_origin_origin_executions_total", originExecutions.sum());
    gauge(output, "bounded_origin_origin_active", executorStats.activeJobs());
    gauge(output, "bounded_origin_origin_queue_depth", executorStats.queuedJobs());
    gauge(output, "bounded_origin_origin_in_flight", executorStats.inFlightJobs());
    gauge(output, "bounded_origin_failure_cooldown_entries", executorStats.cooldownEntries());
    gauge(output, "bounded_origin_client_connections", clientConnections);
    gauge(output, "bounded_origin_active_requests", activeRequests);
    gauge(output, "bounded_origin_origin_connections", originConnections);
    gauge(output, "bounded_origin_origin_pending_acquires", originPendingAcquires);
    gauge(output, "bounded_origin_spool_bytes", spoolBytes);
    gauge(output, "bounded_origin_spool_files", spoolFiles);
    counter(output, "bounded_origin_single_flight_joins_total", singleFlightJoins.sum());
    counter(output, "bounded_origin_rejections_total", rejections.sum());
    counter(output, "bounded_origin_origin_duration_seconds_count", originDurationCount.sum());
    doubleMetric(
        output,
        "bounded_origin_origin_duration_seconds_sum",
        originDurationNanos.sum() / 1_000_000_000.0d);
    counter(
        output,
        "bounded_origin_materialization_duration_seconds_count",
        materializationDurationCount.sum());
    doubleMetric(
        output,
        "bounded_origin_materialization_duration_seconds_sum",
        materializationDurationNanos.sum() / 1_000_000_000.0d);
    counter(output, "bounded_origin_bytes_served_total", bytesServed.sum());
    counter(output, "bounded_origin_bytes_stored_total", bytesStored.sum());
    counter(output, "bounded_origin_client_compute_decisions_total", clientComputeDecisions.sum());
    counter(output, "bounded_origin_request_body_bytes_total", requestBodyBytes.sum());
    counter(output, "bounded_origin_origin_response_bytes_total", originResponseBytes.sum());
    counter(output, "bounded_origin_malformed_requests_total", malformedRequests.sum());
    counter(output, "bounded_origin_store_failures_total", storeFailures.sum());
    counter(output, "bounded_origin_origin_pool_rejections_total", originPoolRejections.sum());
    return output.toString();
  }

  Snapshot snapshot() {
    return new Snapshot(
        requests.sum(),
        artifactHits.sum(),
        artifactMisses.sum(),
        originExecutions.sum(),
        singleFlightJoins.sum(),
        rejections.sum(),
        bytesServed.sum(),
        bytesStored.sum(),
        clientComputeDecisions.sum(),
        malformedRequests.sum(),
        storeFailures.sum(),
        originPoolRejections.sum());
  }

  private static void counter(StringBuilder output, String name, long value) {
    output.append("# TYPE ").append(name).append(" counter\n");
    output.append(name).append(' ').append(value).append('\n');
  }

  private static void gauge(StringBuilder output, String name, long value) {
    output.append("# TYPE ").append(name).append(" gauge\n");
    output.append(name).append(' ').append(value).append('\n');
  }

  private static void doubleMetric(StringBuilder output, String name, double value) {
    output.append(name).append(' ').append(value).append('\n');
  }

  record Snapshot(
      long requests,
      long artifactHits,
      long artifactMisses,
      long originExecutions,
      long singleFlightJoins,
      long rejections,
      long bytesServed,
      long bytesStored,
      long clientComputeDecisions,
      long malformedRequests,
      long storeFailures,
      long originPoolRejections) {}
}
