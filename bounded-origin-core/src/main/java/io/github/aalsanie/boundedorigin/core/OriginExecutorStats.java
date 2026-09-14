package io.github.aalsanie.boundedorigin.core;

public record OriginExecutorStats(
    int activeJobs, int queuedJobs, int inFlightJobs, int cooldownEntries) {
  public OriginExecutorStats {
    if (activeJobs < 0 || queuedJobs < 0 || inFlightJobs < 0 || cooldownEntries < 0) {
      throw new IllegalArgumentException("executor statistics must be non-negative");
    }
    if (activeJobs + queuedJobs > inFlightJobs) {
      throw new IllegalArgumentException("active and queued jobs cannot exceed in-flight jobs");
    }
  }
}
