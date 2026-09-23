package io.github.aalsanie.boundedorigin.proxy;

/**
 * The policy owner's declaration about the audience and lifetime of an HTTP operation's result.
 * Every recipient must be authorized to receive the complete representation without a per-caller
 * origin decision. Neither contract supports personalized responses or authorization-dependent
 * data. The operation represents computation of a result, not per-caller side effects. The origin
 * must affirm public sharing with Cache-Control: public; only the additional no-transform directive
 * is supported. Privacy, expiration, revalidation, partial/conditional responses and unselected
 * Vary inputs fail closed before sharing. This adapter does not implement HTTP cache revalidation.
 */
public enum RepresentationContract {
  /** Equivalent concurrent callers may share a public result; it is not durably reusable. */
  PUBLIC,

  /**
   * The complete public representation is invariant for this operation key, including across
   * restart. Changes require a different operation or policy/materializer version. This is an
   * application contract, not the freshness-scoped HTTP Cache-Control immutable directive.
   */
  PUBLIC_IMMUTABLE
}
