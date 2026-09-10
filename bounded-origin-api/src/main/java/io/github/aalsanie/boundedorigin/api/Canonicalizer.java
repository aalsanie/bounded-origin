package io.github.aalsanie.boundedorigin.api;

@FunctionalInterface
public interface Canonicalizer {
  String canonicalize(Operation operation);
}
