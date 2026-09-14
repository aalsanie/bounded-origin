package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactResponseWriterTransportFailureTest {
  @TempDir Path temporaryDirectory;

  @Test
  void ioFailureWritingIntermediateContentIsPropagatedWithoutWrapping() throws Exception {
    IOException writeFailure = new IOException("downstream write failed");
    EmbeddedChannel channel = new EmbeddedChannel(new FailingContentHandler(writeFailure));
    try {
      Result result = write(channel, artifact(new byte[] {1, 2, 3}));
      assertSame(writeFailure, result.failure());
      assertFalse(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void nonIoFailureWritingIntermediateContentIsWrappedAsIoFailure() throws Exception {
    IllegalStateException writeFailure = new IllegalStateException("downstream failed");
    EmbeddedChannel channel = new EmbeddedChannel(new FailingContentHandler(writeFailure));
    try {
      Result result = write(channel, artifact(new byte[] {1, 2, 3}));
      IOException failure = assertInstanceOf(IOException.class, result.failure());
      assertSame(writeFailure, failure.getCause());
      assertFalse(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void fullSizedIntermediateChunkAndShortFinalChunkAreBothStreamed() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    try {
      Result result = write(channel, artifact(new byte[] {1, 2, 3}));
      assertEquals(null, result.failure());

      Object headers = channel.readOutbound();
      try {
        assertTrue(headers instanceof HttpResponse);
      } finally {
        ReferenceCountUtil.release(headers);
      }

      Object first = channel.readOutbound();
      try {
        assertTrue(first instanceof HttpContent);
        assertFalse(first instanceof LastHttpContent);
        assertEquals(2, ((HttpContent) first).content().readableBytes());
      } finally {
        ReferenceCountUtil.release(first);
      }

      Object last = channel.readOutbound();
      try {
        assertTrue(last instanceof LastHttpContent);
        assertEquals(1, ((HttpContent) last).content().readableBytes());
      } finally {
        ReferenceCountUtil.release(last);
      }
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private Result write(EmbeddedChannel channel, Artifact artifact) throws Exception {
    ArtifactResponseWriter writer = new ArtifactResponseWriter(config(), new GatewayMetrics());
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    writer.write(
        channel,
        "GET",
        true,
        false,
        artifact,
        throwable -> {
          failure.set(throwable);
          completed.countDown();
        });
    assertTrue(completed.await(2, TimeUnit.SECONDS));
    return new Result(failure.get());
  }

  private Artifact artifact(byte[] bytes) {
    return new Artifact(200, bytes.length, Map.of(), () -> new ByteArrayInputStream(bytes.clone()));
  }

  private GatewayConfig config() throws Exception {
    return GatewayTestFixtures.config(
        GatewayTestFixtures.unusedPort(),
        temporaryDirectory,
        Map.of(
            "http.chunk-bytes", "2",
            "http.chunked-response-threshold-bytes", "1"));
  }

  private record Result(Throwable failure) {}

  private static final class FailingContentHandler extends ChannelOutboundHandlerAdapter {
    private final Throwable failure;

    private FailingContentHandler(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      if (message instanceof HttpContent && !(message instanceof LastHttpContent)) {
        ReferenceCountUtil.release(message);
        promise.setFailure(failure);
        return;
      }
      context.write(message, promise);
    }
  }
}
