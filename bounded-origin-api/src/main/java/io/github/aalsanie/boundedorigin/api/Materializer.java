package io.github.aalsanie.boundedorigin.api;

@FunctionalInterface
public interface Materializer {
  Artifact materialize(Operation operation) throws MaterializationException;
}
