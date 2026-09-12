package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.core.OriginExecutorStats;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactResponseWriterMutationContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void chunkingStartsStrictlyAboveTheConfiguredThreshold() throws Exception {
    GatewayConfig config = config(32);
    GatewayMetrics metrics = new GatewayMetrics();

    EmbeddedChannel exact = new EmbeddedChannel();
    try {
      byte[] body = new byte[32];
      assertNull(write(config, metrics, exact, artifact(body), "GET").failure());
      Object outbound = exact.readOutbound();
      try {
        HttpResponse response = (HttpResponse) outbound;
        assertEquals("32", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertFalse(response.headers().contains(HttpHeaderNames.TRANSFER_ENCODING));
      } finally {
        ReferenceCountUtil.release(outbound);
      }
      drain(exact);
    } finally {
      exact.finishAndReleaseAll();
    }

    EmbeddedChannel over = new EmbeddedChannel();
    try {
      byte[] body = new byte[33];
      assertNull(write(config, metrics, over, artifact(body), "GET").failure());
      Object outbound = over.readOutbound();
      try {
        HttpResponse response = (HttpResponse) outbound;
        assertEquals("chunked", response.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        assertFalse(response.headers().contains(HttpHeaderNames.CONTENT_LENGTH));
      } finally {
        ReferenceCountUtil.release(outbound);
      }
      drain(over);
    } finally {
      over.finishAndReleaseAll();
    }

    String prometheus = metrics.prometheus(new OriginExecutorStats(0, 0, 0, 0), 0, 0, 0, 0, 0, 0);
    assertTrue(prometheus.contains("bounded_origin_bytes_served_total 65\n"));
  }

  @Test
  void representationLengthAcceptsBothDecimalCharacterBoundaries() throws Exception {
    GatewayConfig config = config(32);
    GatewayMetrics metrics = new GatewayMetrics();

    for (String value : new String[] {"0", "9"}) {
      EmbeddedChannel channel = new EmbeddedChannel();
      try {
        Artifact artifact =
            new Artifact(
                200,
                0,
                Map.of(HttpRequestSecurity.REPRESENTATION_CONTENT_LENGTH, value),
                () -> new ByteArrayInputStream(new byte[0]));
        assertNull(write(config, metrics, channel, artifact, "HEAD").failure());
        Object outbound = channel.readOutbound();
        try {
          assertEquals(value, ((HttpResponse) outbound).headers().get(HttpHeaderNames.CONTENT_LENGTH));
        } finally {
          ReferenceCountUtil.release(outbound);
        }
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  private GatewayConfig config(long threshold) throws Exception {
    return GatewayTestFixtures.config(
        GatewayTestFixtures.unusedPort(),
        temporaryDirectory,
        Map.of("http.chunked-response-threshold-bytes", Long.toString(threshold)));
  }

  private static Artifact artifact(byte[] body) {
    return new Artifact(200, body.length, Map.of(), () -> new ByteArrayInputStream(body));
  }

  private static Result write(
      GatewayConfig config,
      GatewayMetrics metrics,
      EmbeddedChannel channel,
      Artifact artifact,
      String method)
      throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    new ArtifactResponseWriter(config, metrics)
        .write(
            channel,
            method,
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

  private static void drain(EmbeddedChannel channel) {
    Object message;
    while ((message = channel.readOutbound()) != null) {
      ReferenceCountUtil.release(message);
    }
  }

  private record Result(Throwable failure) {}
}
