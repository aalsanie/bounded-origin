package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.core.OriginExecutionException;
import io.github.aalsanie.boundedorigin.core.OriginExecutionFailure;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class GatewayRequestHandler extends ChannelInboundHandlerAdapter {
  private static final AtomicLong REQUEST_IDS = new AtomicLong();

  private final GatewayConfig config;
  private final GatewayRequestProcessor processor;
  private final ArtifactResponseWriter responseWriter;
  private final GatewayMetrics metrics;
  private final GatewayRuntimeState runtime;
  private final SpoolQuota spoolQuota;

  private RequestState current;
  private boolean registered;

  GatewayRequestHandler(
      GatewayConfig config,
      GatewayRequestProcessor processor,
      ArtifactResponseWriter responseWriter,
      GatewayMetrics metrics,
      GatewayRuntimeState runtime,
      SpoolQuota spoolQuota) {
    this.config = Objects.requireNonNull(config, "config");
    this.processor = Objects.requireNonNull(processor, "processor");
    this.responseWriter = Objects.requireNonNull(responseWriter, "responseWriter");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.spoolQuota = Objects.requireNonNull(spoolQuota, "spoolQuota");
  }

  @Override
  public void channelActive(ChannelHandlerContext context) {
    registered = runtime.register(context.channel());
    if (!registered) {
      metrics.rejection();
      context.close();
      return;
    }
    context.read();
  }

  @Override
  public void channelRead(ChannelHandlerContext context, Object message) {
    try {
      if (message instanceof HttpRequest request) {
        onRequest(context, request);
      }
      if (message instanceof HttpContent content) {
        onContent(context, content);
      }
    } finally {
      ReferenceCountUtil.release(message);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext context) {
    RequestState state = current;
    if (state != null) {
      terminate(state);
    }
    if (registered) {
      registered = false;
      runtime.unregister(context.channel());
    }
    context.fireChannelInactive();
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext context, Object event) {
    if (event instanceof IdleStateEvent) {
      context.close();
    } else {
      context.fireUserEventTriggered(event);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
    RequestState state = current;
    if (state != null) {
      StructuredLog.failure(state.requestId, "client_connection_failure", cause);
      terminate(state);
    }
    context.close();
  }

  private void onRequest(ChannelHandlerContext context, HttpRequest request) {
    if (current != null) {
      metrics.malformedRequest();
      context.close();
      return;
    }
    metrics.request();
    if (!runtime.beginRequest()) {
      metrics.rejection();
      writeStandalone(
          context,
          request.method().name(),
          false,
          ResponseArtifacts.text(
              503,
              "gateway is draining\n",
              Map.of("retry-after", Integer.toString(config.retryAfterSeconds()))));
      return;
    }

    long requestId = REQUEST_IDS.incrementAndGet();
    HttpRequestSecurity.ValidatedRequest validated;
    try {
      validated = HttpRequestSecurity.validate(request, config.maxRequestBodyBytes());
    } catch (HttpRequestSecurity.HttpContractException exception) {
      if (exception.status() == 400) {
        metrics.malformedRequest();
      }
      RequestState failed =
          RequestState.failed(
              requestId,
              request.method().name(),
              safePath(request.uri()),
              System.nanoTime(),
              runtime);
      current = failed;
      respondError(context, failed, exception.status(), exception.getMessage() + '\n', true, false);
      return;
    }

    StreamingSpool spool;
    try {
      spool =
          new StreamingSpool(
              config.temporaryDirectory(),
              "client-request-",
              config.maxRequestBodyBytes(),
              spoolQuota);
    } catch (SpoolQuota.SpoolLimitExceededException exception) {
      RequestState failed =
          RequestState.failed(
              requestId, validated.method(), validated.path(), System.nanoTime(), runtime);
      current = failed;
      metrics.rejection();
      respondError(context, failed, 503, "temporary capacity unavailable\n", true, true);
      return;
    } catch (IOException exception) {
      RequestState failed =
          RequestState.failed(
              requestId, validated.method(), validated.path(), System.nanoTime(), runtime);
      current = failed;
      StructuredLog.failure(requestId, "request_spool_failure", exception);
      respondError(context, failed, 500, "request buffering failed\n", true, false);
      return;
    }

    Map<String, List<String>> originHeaders =
        HttpRequestSecurity.originRequestHeaders(
            request.headers(), 0, config.trustForwardedHeaders());
    RequestState state =
        new RequestState(
            requestId,
            validated,
            originHeaders,
            HttpUtil.isKeepAlive(request),
            spool,
            System.nanoTime(),
            runtime);
    current = state;
    state.timeout =
        context
            .executor()
            .schedule(
                () -> requestTimedOut(context, state),
                config.requestTimeout().toNanos(),
                TimeUnit.NANOSECONDS);

    if (HttpUtil.is100ContinueExpected(request)) {
      context
          .writeAndFlush(
              new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
          .addListener(
              ignored -> {
                if (!state.terminal.get() && context.channel().isActive()) {
                  context.read();
                }
              });
    } else {
      context.read();
    }
  }

  private void onContent(ChannelHandlerContext context, HttpContent content) {
    RequestState state = current;
    if (state == null || state.validated == null || state.terminal.get()) {
      context.close();
      return;
    }
    if (!content.decoderResult().isSuccess()) {
      metrics.malformedRequest();
      respondError(context, state, 400, "malformed request body\n", true, false);
      return;
    }

    ByteBuf buffer = content.content();
    int readable = buffer.readableBytes();
    long nextLength;
    try {
      nextLength = Math.addExact(state.receivedBytes, readable);
    } catch (ArithmeticException exception) {
      respondError(context, state, 413, "request body exceeds configured limit\n", true, false);
      return;
    }
    if (state.validated.declaredContentLength() >= 0
        && nextLength > state.validated.declaredContentLength()) {
      metrics.malformedRequest();
      respondError(context, state, 400, "request body exceeds Content-Length\n", true, false);
      return;
    }
    state.receivedBytes = nextLength;
    byte[] bytes = new byte[readable];
    buffer.getBytes(buffer.readerIndex(), bytes);
    boolean last = content instanceof LastHttpContent;

    try {
      state
          .spool
          .append(bytes)
          .whenComplete(
              (ignored, failure) ->
                  context
                      .executor()
                      .execute(
                          () -> {
                            if (state.terminal.get()) {
                              return;
                            }
                            if (failure != null) {
                              Throwable cause = unwrap(failure);
                              if (cause instanceof StreamingSpool.BodyLimitExceededException) {
                                respondError(
                                    context,
                                    state,
                                    413,
                                    "request body exceeds configured limit\n",
                                    true,
                                    false);
                              } else if (cause
                                  instanceof StreamingSpool.SpoolCapacityExceededException) {
                                metrics.rejection();
                                respondError(
                                    context,
                                    state,
                                    503,
                                    "temporary capacity unavailable\n",
                                    true,
                                    true);
                              } else {
                                StructuredLog.failure(
                                    state.requestId, "request_spool_failure", cause);
                                respondError(
                                    context, state, 500, "request buffering failed\n", true, false);
                              }
                            } else if (last) {
                              finishRequestBody(context, state);
                            } else {
                              context.read();
                            }
                          }));
    } catch (StreamingSpool.BodyLimitExceededException exception) {
      respondError(context, state, 413, "request body exceeds configured limit\n", true, false);
    } catch (StreamingSpool.SpoolCapacityExceededException exception) {
      metrics.rejection();
      respondError(context, state, 503, "temporary capacity unavailable\n", true, true);
    } catch (RuntimeException exception) {
      StructuredLog.failure(state.requestId, "request_spool_failure", exception);
      respondError(context, state, 500, "request buffering failed\n", true, false);
    }
  }

  private void finishRequestBody(ChannelHandlerContext context, RequestState state) {
    if (state.validated.declaredContentLength() >= 0
        && state.receivedBytes != state.validated.declaredContentLength()) {
      metrics.malformedRequest();
      respondError(
          context, state, 400, "request body does not match Content-Length\n", true, false);
      return;
    }

    state
        .spool
        .finish()
        .whenComplete(
            (body, failure) ->
                context
                    .executor()
                    .execute(
                        () -> {
                          if (state.terminal.get()) {
                            if (body != null) {
                              deleteBody(body);
                            }
                            return;
                          }
                          if (failure != null) {
                            StructuredLog.failure(
                                state.requestId, "request_spool_finish_failure", unwrap(failure));
                            respondError(
                                context, state, 500, "request buffering failed\n", true, false);
                            return;
                          }
                          metrics.requestBodyBytes(body.length());
                          state.body = body;
                          state.bodyOwnershipTransferred = true;
                          dispatch(context, state);
                        }));
  }

  private void dispatch(ChannelHandlerContext context, RequestState state) {
    Thread.ofVirtual()
        .name("bounded-origin-dispatch-", 0)
        .start(
            () -> {
              try {
                Map<String, List<String>> headers =
                    withActualContentLength(state.originHeaders, state.body.length());
                GatewayRequestProcessor.Outcome outcome =
                    processor.process(new GatewayRequest(state.validated, headers, state.body));
                context.executor().execute(() -> attachOutcome(context, state, outcome));
              } catch (Throwable throwable) {
                context.executor().execute(() -> processingFailed(context, state, throwable));
              }
            });
  }

  private void attachOutcome(
      ChannelHandlerContext context, RequestState state, GatewayRequestProcessor.Outcome outcome) {
    if (state.terminal.get()) {
      closeLease(outcome.flightLease());
      return;
    }
    state.policyId = outcome.policyId();
    state.flightLease = outcome.flightLease();
    outcome
        .artifact()
        .whenComplete(
            (artifact, failure) ->
                context
                    .executor()
                    .execute(
                        () -> {
                          if (state.terminal.get()) {
                            return;
                          }
                          if (failure != null) {
                            processingFailed(context, state, unwrap(failure));
                          } else {
                            writeArtifact(context, state, artifact);
                          }
                        }));
  }

  private void processingFailed(
      ChannelHandlerContext context, RequestState state, Throwable throwable) {
    Throwable cause = unwrap(throwable);
    StructuredLog.failure(state.requestId, "request_processing_failure", cause);
    if (cause instanceof GatewayRequestProcessor.GatewayStoreException) {
      respondError(context, state, 500, "artifact store unavailable\n", false, false);
      return;
    }
    if (cause instanceof OriginExecutionException execution) {
      OriginExecutionFailure failure = execution.failure();
      switch (failure) {
        case GLOBAL_QUEUE_LIMIT, POLICY_QUEUE_LIMIT, COOLDOWN, CLOSED ->
            respondError(context, state, 503, "origin capacity unavailable\n", false, true);
        case TIMEOUT ->
            respondError(context, state, 504, "origin execution timed out\n", false, false);
        case RESULT_TOO_LARGE ->
            respondError(
                context, state, 502, "origin response exceeds configured limit\n", false, false);
        case MATERIALIZATION_FAILED -> {
          if (containsCause(execution, java.util.concurrent.TimeoutException.class)) {
            respondError(context, state, 504, "origin timed out\n", false, false);
          } else if (containsCause(execution, OriginConnectionPool.PoolExhaustedException.class)
              || containsCause(execution, OriginConnectionPool.PoolClosedException.class)
              || containsCause(execution, StreamingSpool.SpoolCapacityExceededException.class)
              || containsCause(execution, SpoolQuota.SpoolLimitExceededException.class)) {
            respondError(context, state, 503, "origin capacity unavailable\n", false, true);
          } else {
            respondError(context, state, 502, "origin request failed\n", false, false);
          }
        }
        case INTERNAL_ERROR ->
            respondError(context, state, 500, "origin executor failed\n", false, false);
      }
      return;
    }
    respondError(context, state, 500, "gateway processing failed\n", false, false);
  }

  private void writeArtifact(ChannelHandlerContext context, RequestState state, Artifact artifact) {
    if (state.terminal.get()) {
      return;
    }
    state.responseStarted = true;
    try {
      responseWriter.write(
          context.channel(),
          state.validated.method(),
          state.keepAlive,
          runtime.draining(),
          artifact,
          failure ->
              context
                  .executor()
                  .execute(() -> responseFinished(context, state, artifact.statusCode(), failure)));
    } catch (RuntimeException exception) {
      responseFinished(context, state, 500, exception);
    }
  }

  private void respondError(
      ChannelHandlerContext context,
      RequestState state,
      int status,
      String message,
      boolean closeAfter,
      boolean retryAfter) {
    if (state.terminal.get() || state.responseStarted) {
      context.close();
      terminate(state);
      return;
    }
    if (status == 503) {
      metrics.rejection();
    }
    Artifact artifact =
        retryAfter
            ? ResponseArtifacts.text(
                status,
                message,
                Map.of("retry-after", Integer.toString(config.retryAfterSeconds())))
            : ResponseArtifacts.text(status, message);
    state.forceClose |= closeAfter;
    state.responseStarted = true;
    String method = state.validated == null ? state.method : state.validated.method();
    try {
      responseWriter.write(
          context.channel(),
          method,
          state.keepAlive && !state.forceClose,
          runtime.draining(),
          artifact,
          failure ->
              context.executor().execute(() -> responseFinished(context, state, status, failure)));
    } catch (RuntimeException exception) {
      responseFinished(context, state, status, exception);
    }
  }

  private void writeStandalone(
      ChannelHandlerContext context, String method, boolean keepAlive, Artifact artifact) {
    try {
      responseWriter.write(
          context.channel(),
          method,
          keepAlive,
          true,
          artifact,
          ignored -> context.executor().execute(context::close));
    } catch (RuntimeException exception) {
      context.close();
    }
  }

  private void responseFinished(
      ChannelHandlerContext context, RequestState state, int status, Throwable failure) {
    if (!state.terminal.compareAndSet(false, true)) {
      return;
    }
    cancelTimeout(state);
    closeLease(state.flightLease);
    state.flightLease = null;
    if (state.spool != null && !state.bodyOwnershipTransferred) {
      state.spool.close();
    }
    StructuredLog.request(
        state.requestId,
        state.validated == null ? state.method : state.validated.method(),
        state.validated == null ? state.path : state.validated.path(),
        state.policyId,
        status,
        System.nanoTime() - state.startedNanos);
    state.finishRuntime();
    if (current == state) {
      current = null;
    }
    if (failure != null) {
      StructuredLog.failure(state.requestId, "client_response_failure", failure);
      context.close();
    } else if (state.forceClose || !state.keepAlive || runtime.draining()) {
      context.close();
    } else if (context.channel().isActive()) {
      context.read();
    }
  }

  private void requestTimedOut(ChannelHandlerContext context, RequestState state) {
    if (state.terminal.get()) {
      return;
    }
    metrics.rejection();
    if (state.responseStarted) {
      context.close();
      terminate(state);
    } else {
      respondError(context, state, 408, "request timed out\n", true, false);
    }
  }

  private void terminate(RequestState state) {
    if (!state.terminal.compareAndSet(false, true)) {
      return;
    }
    cancelTimeout(state);
    closeLease(state.flightLease);
    state.flightLease = null;
    if (state.spool != null && !state.bodyOwnershipTransferred) {
      state.spool.close();
    }
    state.finishRuntime();
    if (current == state) {
      current = null;
    }
  }

  private static Map<String, List<String>> withActualContentLength(
      Map<String, List<String>> headers, long length) {
    java.util.LinkedHashMap<String, List<String>> copy = new java.util.LinkedHashMap<>(headers);
    copy.put("content-length", List.of(Long.toString(length)));
    return Map.copyOf(copy);
  }

  private static void cancelTimeout(RequestState state) {
    ScheduledFuture<?> timeout = state.timeout;
    if (timeout != null) {
      timeout.cancel(false);
      state.timeout = null;
    }
  }

  private static void closeLease(FlightLeaseRegistry.Lease lease) {
    if (lease != null) {
      lease.close();
    }
  }

  private static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static boolean containsCause(Throwable throwable, Class<? extends Throwable> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static String safePath(String uri) {
    if (uri == null) {
      return "-";
    }
    int query = uri.indexOf('?');
    return query < 0 ? uri : uri.substring(0, query);
  }

  private static void deleteBody(StreamingSpool.Result body) {
    try {
      body.close();
    } catch (RuntimeException exception) {
      System.getLogger(GatewayRequestHandler.class.getName())
          .log(System.Logger.Level.WARNING, "failed to release completed request spool", exception);
    }
  }

  private static final class RequestState {
    private final long requestId;
    private final HttpRequestSecurity.ValidatedRequest validated;
    private final String method;
    private final String path;
    private final Map<String, List<String>> originHeaders;
    private final boolean keepAlive;
    private final StreamingSpool spool;
    private final long startedNanos;
    private final GatewayRuntimeState runtime;
    private final SpoolQuota spoolQuota;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean runtimeFinished = new AtomicBoolean();

    private long receivedBytes;
    private boolean responseStarted;
    private boolean forceClose;
    private boolean bodyOwnershipTransferred;
    private String policyId = "-";
    private ScheduledFuture<?> timeout;
    private StreamingSpool.Result body;
    private FlightLeaseRegistry.Lease flightLease;

    private RequestState(
        long requestId,
        HttpRequestSecurity.ValidatedRequest validated,
        Map<String, List<String>> originHeaders,
        boolean keepAlive,
        StreamingSpool spool,
        long startedNanos,
        GatewayRuntimeState runtime) {
      this.requestId = requestId;
      this.validated = validated;
      this.method = validated.method();
      this.path = validated.path();
      this.originHeaders = originHeaders;
      this.keepAlive = keepAlive;
      this.spool = spool;
      this.startedNanos = startedNanos;
      this.runtime = runtime;
    }

    private RequestState(
        long requestId,
        String method,
        String path,
        long startedNanos,
        GatewayRuntimeState runtime) {
      this.requestId = requestId;
      this.validated = null;
      this.method = method;
      this.path = path;
      this.originHeaders = Map.of();
      this.keepAlive = false;
      this.spool = null;
      this.startedNanos = startedNanos;
      this.runtime = runtime;
    }

    static RequestState failed(
        long requestId,
        String method,
        String path,
        long startedNanos,
        GatewayRuntimeState runtime) {
      return new RequestState(requestId, method, path, startedNanos, runtime);
    }

    void finishRuntime() {
      if (runtimeFinished.compareAndSet(false, true)) {
        runtime.finishRequest();
      }
    }
  }
}
