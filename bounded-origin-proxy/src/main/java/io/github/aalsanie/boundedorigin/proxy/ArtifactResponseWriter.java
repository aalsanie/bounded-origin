package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ArtifactResponseWriter {
  private final GatewayConfig config;
  private final GatewayMetrics metrics;

  ArtifactResponseWriter(GatewayConfig config, GatewayMetrics metrics) {
    this.config = Objects.requireNonNull(config, "config");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  void write(
      Channel channel,
      String requestMethod,
      boolean requestKeepAlive,
      boolean draining,
      Artifact artifact,
      Consumer<Throwable> completion) {
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(requestMethod, "requestMethod");
    Objects.requireNonNull(artifact, "artifact");
    Objects.requireNonNull(completion, "completion");

    Map<String, String> metadata = HttpRequestSecurity.safeArtifactMetadata(artifact.metadata());
    Long representationLength = representationLength(artifact.metadata());
    boolean head = HttpMethod.HEAD.name().equals(requestMethod);
    boolean statusWithoutBody =
        artifact.statusCode() == 204
            || artifact.statusCode() == 205
            || artifact.statusCode() == 304;
    if (statusWithoutBody && artifact.contentLength() != 0) {
      throw new IllegalArgumentException("body-forbidden response has a non-zero artifact length");
    }
    boolean keepAlive = requestKeepAlive && !draining;
    boolean chunked =
        !head
            && !statusWithoutBody
            && artifact.contentLength() > config.chunkedResponseThresholdBytes();
    AtomicBoolean completed = new AtomicBoolean();
    Consumer<Throwable> complete =
        failure -> {
          if (completed.compareAndSet(false, true)) {
            completion.accept(failure);
          }
        };

    Thread.ofVirtual()
        .name("bounded-origin-response-", 0)
        .start(
            () -> {
              try {
                if (head || statusWithoutBody || artifact.contentLength() == 0) {
                  HttpResponse response =
                      new DefaultFullHttpResponse(
                          HttpVersion.HTTP_1_1,
                          HttpResponseStatus.valueOf(artifact.statusCode()),
                          Unpooled.EMPTY_BUFFER);
                  prepareHeaders(
                      response,
                      artifact,
                      metadata,
                      representationLength,
                      head,
                      statusWithoutBody,
                      false,
                      keepAlive);
                  writeFinal(channel, response, complete);
                  return;
                }

                HttpResponse response =
                    new DefaultHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(artifact.statusCode()));
                prepareHeaders(
                    response,
                    artifact,
                    metadata,
                    representationLength,
                    false,
                    false,
                    chunked,
                    keepAlive);
                sync(channel.writeAndFlush(response));
                streamBody(channel, artifact, complete);
              } catch (Throwable throwable) {
                channel.close();
                complete.accept(throwable);
              }
            });
  }

  private static void prepareHeaders(
      HttpResponse response,
      Artifact artifact,
      Map<String, String> metadata,
      Long representationLength,
      boolean head,
      boolean statusWithoutBody,
      boolean chunked,
      boolean keepAlive) {
    metadata.forEach(response.headers()::set);
    if (head) {
      response
          .headers()
          .set(
              HttpHeaderNames.CONTENT_LENGTH,
              representationLength == null ? artifact.contentLength() : representationLength);
    } else if (artifact.statusCode() == 304) {
      response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
      if (representationLength != null) {
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, representationLength);
      } else {
        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
      }
    } else if (statusWithoutBody) {
      response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
      response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
    } else if (chunked) {
      HttpUtil.setTransferEncodingChunked(response, true);
    } else {
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, artifact.contentLength());
    }
    HttpUtil.setKeepAlive(response, keepAlive);
  }

  private static Long representationLength(Map<String, String> metadata) {
    String value = metadata.get(HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH);
    if (value == null) {
      return null;
    }
    if (value.isEmpty()) {
      throw new IllegalArgumentException("artifact representation length is empty");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') {
        throw new IllegalArgumentException("artifact representation length is invalid");
      }
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("artifact representation length is invalid", exception);
    }
  }

  private void streamBody(
      Channel channel, Artifact artifact, Consumer<Throwable> completion)
      throws IOException, InterruptedException {
    byte[] buffer = new byte[config.maxChunkSize()];
    long remaining = artifact.contentLength();
    try (InputStream input = artifact.body().openStream()) {
      while (remaining > 0) {
        int maximum = (int) Math.min(buffer.length, remaining);
        int count = input.read(buffer, 0, maximum);
        if (count < 0) {
          throw new IOException("artifact body ended before its declared length");
        }
        if (count == 0) {
          continue;
        }
        remaining -= count;
        byte[] chunk = count == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, count);
        if (remaining == 0) {
          if (input.read() >= 0) {
            throw new IOException("artifact body exceeds its declared length");
          }
          metrics.bytesServed(artifact.contentLength());
          writeFinal(
              channel,
              new DefaultLastHttpContent(Unpooled.wrappedBuffer(chunk)),
              completion);
          return;
        }
        sync(channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(chunk))));
      }
    }
    throw new IOException("artifact body did not produce a final content chunk");
  }

  private static void writeFinal(
      Channel channel, Object message, Consumer<Throwable> completion) {
    ChannelPromise promise = channel.newPromise();
    promise.addListener(future -> completion.accept(future.isSuccess() ? null : future.cause()));
    channel.writeAndFlush(message, promise);
  }

  private static void sync(ChannelFuture future) throws IOException, InterruptedException {
    future.sync();
    if (!future.isSuccess()) {
      Throwable cause = future.cause();
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      throw new IOException("client channel write failed", cause);
    }
  }
}
