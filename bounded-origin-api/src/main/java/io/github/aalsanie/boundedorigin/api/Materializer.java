package io.github.aalsanie.boundedorigin.api;

@FunctionalInterface
public interface Materializer {
  /**
   * Runs the operation synchronously. Returning or throwing must end its expensive computation,
   * unless the implementation independently retains bounded ownership of unfinished work.
   * Interruption is a request to stop, not evidence that computation has terminated. A returned
   * artifact transfers ownership of its body to the executor; do not reuse an owned body across
   * independent invocations.
   */
  Artifact materialize(Operation operation) throws MaterializationException;
}
