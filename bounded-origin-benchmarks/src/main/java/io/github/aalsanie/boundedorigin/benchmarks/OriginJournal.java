package io.github.aalsanie.boundedorigin.benchmarks;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

final class OriginJournal implements Closeable {
  private final BufferedWriter output;
  private long starts;
  private long completed;
  private long active;
  private long peak;
  private long cpu;
  private long writeFailures;

  OriginJournal(Path path) throws IOException {
    output = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  synchronized long start(String target) throws IOException {
    long id = ++starts;
    active++;
    peak = Math.max(peak, active);
    event("start", id, target, 0);
    return id;
  }

  synchronized void finish(long id, String target, long nanos) throws IOException {
    active--;
    completed++;
    cpu += nanos;
    event("finish", id, target, nanos);
  }

  synchronized void writeFailed() {
    writeFailures++;
  }

  synchronized String snapshot() {
    long processCpu =
        ProcessHandle.current()
            .info()
            .totalCpuDuration()
            .map(duration -> duration.toNanos())
            .orElse(-1L);
    return "{\"starts\":"
        + starts
        + ",\"completed\":"
        + completed
        + ",\"active\":"
        + active
        + ",\"peak\":"
        + peak
        + ",\"work_cpu_ns\":"
        + cpu
        + ",\"write_failures\":"
        + writeFailures
        + ",\"process_cpu_ns\":"
        + processCpu
        + "}";
  }

  private void event(String type, long id, String target, long nanos) throws IOException {
    String encoded = Base64.getEncoder().encodeToString(target.getBytes(StandardCharsets.UTF_8));
    output.write(
        "{\"event\":\""
            + type
            + "\",\"id\":"
            + id
            + ",\"target_base64\":\""
            + encoded
            + "\",\"monotonic_ns\":"
            + System.nanoTime()
            + ",\"active\":"
            + active
            + ",\"cpu_ns\":"
            + nanos
            + "}\n");
    output.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    output.close();
  }
}
