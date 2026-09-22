package io.github.aalsanie.boundedorigin.api;

@FunctionalInterface
public interface Materializer {
  /**
   * Runs the operation synchronously. Returning or throwing must end its expensive computation,
   * unless the implementation independently retains bounded ownership of unfinished work.
   * Interruption is a request to stop, not evidence that computation has terminated.
   */
  Artifact materialize(Operation operation) throws MaterializationException;
}
