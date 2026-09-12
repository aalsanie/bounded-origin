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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactResponseWriterBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void bodyForbiddenStatusesNeverEmitBodyFraming() throws Exception {
    for (int status : new int[] {204, 205}) {
      EmbeddedChannel channel = new EmbeddedChannel();
      try {
        Result result =
            write(
                channel,
                "GET",
                true,
                false,
                new Artifact(
                    status,
                    0,
                    Map.of("content-type", "text/plain"),
                    () -> new ByteArrayInputStream(new byte[0])));
        assertNull(result.failure());

        Object outbound = channel.readOutbound();
        assertTrue(outbound instanceof HttpResponse);
        assertTrue(outbound instanceof LastHttpContent);
        HttpResponse response = (HttpResponse) outbound;
        assertFalse(response.headers().contains(HttpHeaderNames.CONTENT_LENGTH));
        assertFalse(response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
        assertNull(channel.readOutbound());
        ReferenceCountUtil.release(outbound);
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  void bodyForbiddenStatusRejectsNonZeroArtifactLength() throws Exception {
    ArtifactResponseWriter writer = new ArtifactResponseWriter(config(), new GatewayMetrics());
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Artifact artifact =
          new Artifact(204, 1, Map.of(), () -> new ByteArrayInputStream(new byte[] {1}));
      assertThrows(
          IllegalArgumentException.class,
          () -> writer.write(channel, "GET", true, false, artifact, ignored -> {}));
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void headFallsBackToArtifactLengthAndNotModifiedMayOmitRepresentationLength() throws Exception {
    EmbeddedChannel headChannel = new EmbeddedChannel();
    try {
      Artifact head = new Artifact(200, 7, Map.of(), () -> new ByteArrayInputStream(new byte[0]));
      assertNull(write(headChannel, "HEAD", true, false, head).failure());
      Object outbound = headChannel.readOutbound();
      HttpResponse response = (HttpResponse) outbound;
      assertEquals("7", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
      ReferenceCountUtil.release(outbound);
    } finally {
      headChannel.finishAndReleaseAll();
    }

    EmbeddedChannel notModifiedChannel = new EmbeddedChannel();
    try {
      Artifact notModified =
          new Artifact(304, 0, Map.of(), () -> new ByteArrayInputStream(new byte[0]));
      assertNull(write(notModifiedChannel, "GET", true, false, notModified).failure());
      Object outbound = notModifiedChannel.readOutbound();
      HttpResponse response = (HttpResponse) outbound;
      assertFalse(response.headers().contains(HttpHeaderNames.CONTENT_LENGTH));
      assertFalse(response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
      ReferenceCountUtil.release(outbound);
    } finally {
      notModifiedChannel.finishAndReleaseAll();
    }
  }

  @Test
  void fixedLengthResponseClosesConnectionWhileDraining() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      Artifact artifact =
          new Artifact(200, body.length, Map.of(), () -> new ByteArrayInputStream(body));
      assertNull(write(channel, "GET", true, true, artifact).failure());

      Object header = channel.readOutbound();
      assertTrue(header instanceof HttpResponse);
      HttpResponse response = (HttpResponse) header;
      assertEquals("2", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
      assertEquals("close", response.headers().get(HttpHeaderNames.CONNECTION));
      assertFalse(response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
      ReferenceCountUtil.release(header);

      Object content = channel.readOutbound();
      assertTrue(content instanceof LastHttpContent);
      assertEquals(2, ((HttpContent) content).content().readableBytes());
      ReferenceCountUtil.release(content);
      assertNull(channel.readOutbound());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void representationLengthRejectsEmptyNonDecimalAndOverflowValues() throws Exception {
    ArtifactResponseWriter writer = new ArtifactResponseWriter(config(), new GatewayMetrics());
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      for (String value : List.of("", "-1", "a", "9223372036854775808")) {
        Artifact artifact =
            new Artifact(
                200,
                0,
                Map.of(HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH, value),
                () -> new ByteArrayInputStream(new byte[0]));
        assertThrows(
            IllegalArgumentException.class,
            () -> writer.write(channel, "HEAD", true, false, artifact, ignored -> {}));
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void failedFinalWriteIsReportedThroughCompletion() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.close();
    try {
      Artifact artifact =
          new Artifact(200, 0, Map.of(), () -> new ByteArrayInputStream(new byte[0]));

      Result result = write(channel, "GET", true, false, artifact);

      assertNotNull(result.failure());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void bodyLongerThanDeclaredFailsAndClosesChannel() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Artifact artifact =
          new Artifact(200, 1, Map.of(), () -> new ByteArrayInputStream(new byte[] {1, 2}));
      Result result = write(channel, "GET", true, false, artifact);
      assertNotNull(result.failure());
      assertFalse(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void zeroLengthBulkReadIsRetriedInsteadOfEndingTheBody() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Artifact artifact =
          new Artifact(
              200,
              1,
              Map.of(),
              () ->
                  new InputStream() {
                    private boolean returnedZero;
                    private boolean returnedByte;

                    @Override
                    public int read(byte[] target, int offset, int length) {
                      if (!returnedZero) {
                        returnedZero = true;
                        return 0;
                      }
                      if (!returnedByte) {
                        returnedByte = true;
                        target[offset] = 7;
                        return 1;
                      }
                      return -1;
                    }

                    @Override
                    public int read() {
                      return -1;
                    }
                  });

      assertNull(write(channel, "GET", true, false, artifact).failure());
      Object header = channel.readOutbound();
      ReferenceCountUtil.release(header);
      Object content = channel.readOutbound();
      assertTrue(content instanceof LastHttpContent);
      assertEquals(1, ((HttpContent) content).content().readableBytes());
      ReferenceCountUtil.release(content);
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
