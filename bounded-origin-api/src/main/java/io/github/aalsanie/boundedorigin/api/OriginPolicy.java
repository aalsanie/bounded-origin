package io.github.aalsanie.boundedorigin.api;

import java.util.Objects;
import java.util.Optional;

public final class OriginPolicy {
  private final String id;
  private final long version;
  private final int precedence;
  private final ExecutionStrategy strategy;
  private final Canonicalizer canonicalizer;
  private final String materializerVersion;
  private final Budget budget;
  private final ClientComputation clientComputation;

  private OriginPolicy(
      String id,
      long version,
      int precedence,
      ExecutionStrategy strategy,
      Canonicalizer canonicalizer,
      String materializerVersion,
      Budget budget,
      ClientComputation clientComputation) {
    this.id = requireNonBlank(id, "id");
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
    this.version = version;
    this.precedence = precedence;
    this.strategy = Objects.requireNonNull(strategy, "strategy");
    this.canonicalizer = canonicalizer;
    this.materializerVersion = materializerVersion;
    this.budget = budget;
    this.clientComputation = clientComputation;
  }

  public static OriginPolicy artifactOnly(
      String id,
      long version,
      int precedence,
      String materializerVersion,
      Canonicalizer canonicalizer) {
    return keyed(
        id,
        version,
        precedence,
        ExecutionStrategy.ARTIFACT_ONLY,
        materializerVersion,
        canonicalizer,
        null,
        null);
  }

  public static OriginPolicy boundedCompute(
      String id,
      long version,
      int precedence,
      String materializerVersion,
      Canonicalizer canonicalizer,
      Budget budget) {
    return keyed(
        id,
        version,
        precedence,
        ExecutionStrategy.BOUNDED_COMPUTE,
        materializerVersion,
        canonicalizer,
        Objects.requireNonNull(budget, "budget"),
        null);
  }

  public static OriginPolicy materialize(
      String id,
      long version,
      int precedence,
      String materializerVersion,
      Canonicalizer canonicalizer,
      Budget budget) {
    return keyed(
        id,
        version,
        precedence,
        ExecutionStrategy.MATERIALIZE,
        materializerVersion,
        canonicalizer,
        Objects.requireNonNull(budget, "budget"),
        null);
  }

  public static OriginPolicy clientCompute(
      String id,
      long version,
      int precedence,
      String materializerVersion,
      Canonicalizer canonicalizer,
      ClientComputation clientComputation) {
    return keyed(
        id,
        version,
        precedence,
        ExecutionStrategy.CLIENT_COMPUTE,
        materializerVersion,
        canonicalizer,
        null,
        Objects.requireNonNull(clientComputation, "clientComputation"));
  }

  public static OriginPolicy deny(String id, long version, int precedence) {
    return new OriginPolicy(
        id, version, precedence, ExecutionStrategy.DENY, null, null, null, null);
  }

  private static OriginPolicy keyed(
      String id,
      long version,
      int precedence,
      ExecutionStrategy strategy,
      String materializerVersion,
      Canonicalizer canonicalizer,
      Budget budget,
      ClientComputation clientComputation) {
    return new OriginPolicy(
        id,
        version,
        precedence,
        strategy,
        Objects.requireNonNull(canonicalizer, "canonicalizer"),
        requireNonBlank(materializerVersion, "materializerVersion"),
        budget,
        clientComputation);
  }

  public String id() {
    return id;
  }

  public long version() {
    return version;
  }

  public int precedence() {
    return precedence;
  }

  public ExecutionStrategy strategy() {
    return strategy;
  }

  public Optional<Canonicalizer> canonicalizer() {
    return Optional.ofNullable(canonicalizer);
  }

  public Optional<String> materializerVersion() {
    return Optional.ofNullable(materializerVersion);
  }

  public Optional<Budget> budget() {
    return Optional.ofNullable(budget);
  }

  public Optional<ClientComputation> clientComputation() {
    return Optional.ofNullable(clientComputation);
  }

  private static String requireNonBlank(String value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value;
  }
}
