package io.github.aalsanie.boundedorigin.api;

import java.util.Optional;

@FunctionalInterface
public interface PolicyMatcher {
  Optional<Operation> classify(RequestDescriptor request);
}
