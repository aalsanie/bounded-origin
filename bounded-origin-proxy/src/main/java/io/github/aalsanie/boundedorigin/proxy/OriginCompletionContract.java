package io.github.aalsanie.boundedorigin.proxy;

public enum OriginCompletionContract {
  DISABLED,

  /**
   * All computation caused by an operation must have ended before its complete, self-delimited
   * final HTTP response. Disconnect, timeout and cancellation requests do not establish completion.
   * This is an origin application contract, not a property that HTTP framing alone can verify.
   */
  RESPONSE_COMPLETE
}
