package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import org.junit.jupiter.api.Test;

class GatewayMetricsMutationContractTest {
  @Test
  void prometheusOutputIsAnExactContractForEveryGatewayMetric() {
    GatewayMetrics metrics = new GatewayMetrics();
    metrics.request();
    metrics.artifactHit();
    metrics.artifactMiss();
    metrics.originExecution();
    metrics.singleFlightJoin();
    metrics.rejection();
    metrics.originDuration(1_500_000_000L);
    metrics.materializationDuration(2_250_000_000L);
    metrics.bytesServed(11);
    metrics.bytesStored(12);
    metrics.clientComputeDecision();
    metrics.requestBodyBytes(13);
    metrics.originResponseBytes(14);
    metrics.malformedRequest();
    metrics.storeFailure();
    metrics.originPoolRejection();

    String output = metrics.prometheus(new OriginExecutorStats(1, 2, 3, 4), 5, 6, 7, 8, 9, 10);

    assertEquals(
        """
        # TYPE bounded_origin_requests_total counter
        bounded_origin_requests_total 1
        # TYPE bounded_origin_artifact_hits_total counter
        bounded_origin_artifact_hits_total 1
        # TYPE bounded_origin_artifact_misses_total counter
        bounded_origin_artifact_misses_total 1
        # TYPE bounded_origin_origin_executions_total counter
        bounded_origin_origin_executions_total 1
        # TYPE bounded_origin_origin_active gauge
        bounded_origin_origin_active 1
        # TYPE bounded_origin_origin_queue_depth gauge
        bounded_origin_origin_queue_depth 2
        # TYPE bounded_origin_origin_in_flight gauge
        bounded_origin_origin_in_flight 3
        # TYPE bounded_origin_failure_cooldown_entries gauge
        bounded_origin_failure_cooldown_entries 4
        # TYPE bounded_origin_client_connections gauge
        bounded_origin_client_connections 5
        # TYPE bounded_origin_active_requests gauge
        bounded_origin_active_requests 6
        # TYPE bounded_origin_origin_connections gauge
        bounded_origin_origin_connections 7
        # TYPE bounded_origin_origin_pending_acquires gauge
        bounded_origin_origin_pending_acquires 8
        # TYPE bounded_origin_spool_bytes gauge
        bounded_origin_spool_bytes 9
        # TYPE bounded_origin_spool_files gauge
        bounded_origin_spool_files 10
        # TYPE bounded_origin_single_flight_joins_total counter
        bounded_origin_single_flight_joins_total 1
        # TYPE bounded_origin_rejections_total counter
        bounded_origin_rejections_total 1
        # TYPE bounded_origin_origin_duration_seconds_count counter
        bounded_origin_origin_duration_seconds_count 1
        bounded_origin_origin_duration_seconds_sum 1.5
        # TYPE bounded_origin_materialization_duration_seconds_count counter
        bounded_origin_materialization_duration_seconds_count 1
        bounded_origin_materialization_duration_seconds_sum 2.25
        # TYPE bounded_origin_bytes_served_total counter
        bounded_origin_bytes_served_total 11
        # TYPE bounded_origin_bytes_stored_total counter
        bounded_origin_bytes_stored_total 12
        # TYPE bounded_origin_client_compute_decisions_total counter
        bounded_origin_client_compute_decisions_total 1
        # TYPE bounded_origin_request_body_bytes_total counter
        bounded_origin_request_body_bytes_total 13
        # TYPE bounded_origin_origin_response_bytes_total counter
        bounded_origin_origin_response_bytes_total 14
        # TYPE bounded_origin_malformed_requests_total counter
        bounded_origin_malformed_requests_total 1
        # TYPE bounded_origin_store_failures_total counter
        bounded_origin_store_failures_total 1
        # TYPE bounded_origin_origin_pool_rejections_total counter
        bounded_origin_origin_pool_rejections_total 1
        """,
        output);

    assertEquals(
        new GatewayMetrics.Snapshot(1, 1, 1, 1, 1, 1, 11, 12, 1, 1, 1, 1), metrics.snapshot());
  }
}
