package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactResponseWriterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void headUsesRepresentationLengthWithoutWritingBody() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Artifact artifact =
          new Artifact(
              200,
              0,
              Map.of(
                  "content-type",
                  "text/plain",
                  HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH,
                  "123"),
              () -> new ByteArrayInputStream(new byte[0]));
      Result result = write(channel, "HEAD", true, false, artifact);
      assertNull(result.failure());

      Object outbound = channel.readOutbound();
      assertTrue(outbound instanceof HttpResponse);
      assertTrue(outbound instanceof LastHttpContent);
      HttpResponse response = (HttpResponse) outbound;
      assertEquals("123", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
      assertFalse(response.headers().contains(HttpHeaderNames.CONNECTION));
      assertEquals("text/plain", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
      assertNull(channel.readOutbound());
      ReferenceCountUtil.release(outbound);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void notModifiedMayCarryRepresentationLengthButNeverBody() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Artifact artifact =
          new Artifact(
              304,
              0,
              Map.of(HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH, "456"),
              () -> new ByteArrayInputStream(new byte[0]));
      Result result = write(channel, "GET", false, false, artifact);
      assertNull(result.failure());

      Object outbound = channel.readOutbound();
      assertTrue(outbound instanceof HttpResponse);
      assertTrue(outbound instanceof LastHttpContent);
      HttpResponse response = (HttpResponse) outbound;
      assertEquals("456", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
      assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
      assertFalse(response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
      assertNull(channel.readOutbound());
      ReferenceCountUtil.release(outbound);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void largeArtifactsAreChunkedAndStreamedExactly() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      byte[] body = "x".repeat(128).getBytes(StandardCharsets.UTF_8);
      Artifact artifact =
          new Artifact(
              200,
              body.length,
              Map.of("content-type", "application/octet-stream"),
              () -> new ByteArrayInputStream(body));
      Result result = write(channel, "GET", true, false, artifact);
      assertNull(result.failure());

      HttpResponse response = channel.readOutbound();
      assertEquals("chunked", response.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
      assertFalse(response.headers().contains(HttpHeaderNames.CONTENT_LENGTH));
      ReferenceCountUtil.release(response);

      int bytes = 0;
      while (true) {
        Object message = channel.readOutbound();
        assertNotNull(message);
        if (message instanceof HttpContent content) {
          bytes += content.content().readableBytes();
        }
        boolean last = message instanceof LastHttpContent;
        ReferenceCountUtil.release(message);
        if (last) {
          break;
        }
      }
      assertEquals(body.length, bytes);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void invalidMetadataAndBodyLengthFailClosed() throws Exception {
    GatewayConfig config = config();
    ArtifactResponseWriter writer = new ArtifactResponseWriter(config, new GatewayMetrics());
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Artifact invalidMetadata =
          new Artifact(
              200,
              0,
              Map.of(HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH, "not-a-number"),
              () -> new ByteArrayInputStream(new byte[0]));
      assertThrows(
          IllegalArgumentException.class,
          () -> writer.write(channel, "HEAD", true, false, invalidMetadata, ignored -> {}));

      Artifact shortBody =
          new Artifact(200, 2, Map.of(), () -> new ByteArrayInputStream(new byte[] {1}));
      Result result = write(channel, "GET", true, false, shortBody);
      assertNotNull(result.failure());
      assertFalse(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private Result write(
      EmbeddedChannel channel,
      String method,
      boolean keepAlive,
      boolean draining,
      Artifact artifact)
      throws Exception {
    ArtifactResponseWriter writer = new ArtifactResponseWriter(config(), new GatewayMetrics());
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    writer.write(
        channel,
        method,
        keepAlive,
        draining,
        artifact,
        throwable -> {
          failure.set(throwable);
          completed.countDown();
        });
    assertTrue(completed.await(2, TimeUnit.SECONDS));
    return new Result(failure.get());
  }

  private GatewayConfig config() throws Exception {
    return GatewayTestFixtures.config(
        GatewayTestFixtures.unusedPort(),
        temporaryDirectory,
        Map.of("http.chunked-response-threshold-bytes", "32"));
  }

  private record Result(Throwable failure) {}
}
