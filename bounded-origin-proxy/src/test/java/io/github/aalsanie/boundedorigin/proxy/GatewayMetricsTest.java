package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import org.junit.jupiter.api.Test;

class GatewayMetricsTest {
  @Test
  void prometheusOutputContainsRequiredP05Metrics() {
    GatewayMetrics metrics = new GatewayMetrics();
    metrics.request();
    metrics.artifactHit();
    metrics.artifactMiss();
    metrics.originExecution();
    metrics.singleFlightJoin();
    metrics.rejection();
    metrics.originDuration(1_000_000_000L);
    metrics.materializationDuration(500_000_000L);
    metrics.bytesServed(11);
    metrics.bytesStored(12);
    metrics.clientComputeDecision();
    metrics.requestBodyBytes(13);
    metrics.originResponseBytes(14);
    metrics.malformedRequest();
    metrics.storeFailure();
    metrics.originPoolRejection();

    String output = metrics.prometheus(new OriginExecutorStats(1, 2, 3, 4), 5, 6, 7, 8, 9, 10);

    assertTrue(output.contains("bounded_origin_requests_total 1\n"));
    assertTrue(output.contains("bounded_origin_artifact_hits_total 1\n"));
    assertTrue(output.contains("bounded_origin_artifact_misses_total 1\n"));
    assertTrue(output.contains("bounded_origin_origin_executions_total 1\n"));
    assertTrue(output.contains("bounded_origin_origin_active 1\n"));
    assertTrue(output.contains("bounded_origin_origin_queue_depth 2\n"));
    assertTrue(output.contains("bounded_origin_single_flight_joins_total 1\n"));
    assertTrue(output.contains("bounded_origin_rejections_total 1\n"));
    assertTrue(output.contains("bounded_origin_origin_duration_seconds_count 1\n"));
    assertTrue(output.contains("bounded_origin_materialization_duration_seconds_count 1\n"));
    assertTrue(output.contains("bounded_origin_bytes_served_total 11\n"));
    assertTrue(output.contains("bounded_origin_bytes_stored_total 12\n"));
    assertTrue(output.contains("bounded_origin_client_compute_decisions_total 1\n"));
    assertTrue(output.contains("bounded_origin_spool_bytes 9\n"));
    assertTrue(output.contains("bounded_origin_spool_files 10\n"));
  }
}
