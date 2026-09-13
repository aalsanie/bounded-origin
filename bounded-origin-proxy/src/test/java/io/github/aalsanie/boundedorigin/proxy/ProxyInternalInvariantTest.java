package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aalsanie.boundedorigin.api.Artifact;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxyInternalInvariantTest {
  @TempDir Path temporaryDirectory;

  @Test
  void privateBoundaryHelpersReturnExactValues() throws Exception {
    assertEquals(
        ".",
        invokeStatic(
            HttpRequestSecurity.class, "decodeEncodedDots", new Class<?>[] {String.class}, "%2e"));
    assertEquals(
        "..",
        invokeStatic(
            HttpRequestSecurity.class,
            "decodeEncodedDots",
            new Class<?>[] {String.class},
            "%2E%2e"));

    assertTrue(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isIpv6Literal",
                new Class<?>[] {String.class},
                "::1"));
    assertFalse(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isIpv6Literal",
                new Class<?>[] {String.class},
                "a:b"));

    assertTrue(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isHeaderName",
                new Class<?>[] {String.class},
                "a"));
    assertTrue(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isHeaderName",
                new Class<?>[] {String.class},
                "z"));
    assertFalse(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isHeaderName",
                new Class<?>[] {String.class},
                "{"));

    assertTrue(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isDecimal",
                new Class<?>[] {String.class},
                "0"));
    assertTrue(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isDecimal",
                new Class<?>[] {String.class},
                "9"));
    assertFalse(
        (boolean)
            invokeStatic(
                HttpRequestSecurity.class,
                "isDecimal",
                new Class<?>[] {String.class},
                ":"));

    assertEquals(
        1,
        invokeStatic(
            OriginConnectionPool.class,
            "durationMillis",
            new Class<?>[] {Duration.class},
            Duration.ofNanos(1)));
    assertEquals(
        1_234,
        invokeStatic(
            OriginConnectionPool.class,
            "durationMillis",
            new Class<?>[] {Duration.class},
            Duration.ofMillis(1_234)));
    assertEquals(
        Integer.MAX_VALUE,
        invokeStatic(
            OriginConnectionPool.class,
            "durationMillis",
            new Class<?>[] {Duration.class},
            Duration.ofMillis((long) Integer.MAX_VALUE + 1)));

    Duration left = Duration.ofSeconds(1);
    Duration right = Duration.ofMillis(1_000);
    assertSame(
        left,
        invokeStatic(
            GatewayConfig.class,
            "minimum",
            new Class<?>[] {Duration.class, Duration.class},
            left,
            right));

    assertEquals(
        "-",
        invokeStatic(
            GatewayRequestHandler.class, "safePath", new Class<?>[] {String.class}, (Object) null));
    assertEquals(
        "/path",
        invokeStatic(
            GatewayRequestHandler.class, "safePath", new Class<?>[] {String.class}, "/path"));
    assertEquals(
        "",
        invokeStatic(
            GatewayRequestHandler.class, "safePath", new Class<?>[] {String.class}, "?query"));
    assertEquals(
        "/path",
        invokeStatic(
            GatewayRequestHandler.class,
            "safePath",
            new Class<?>[] {String.class},
            "/path?query"));

    IllegalStateException root = new IllegalStateException("root");
    Throwable wrapped = new CompletionException(new ExecutionException(root));
    assertSame(
        root,
        invokeStatic(
            NettyOriginClient.class, "unwrap", new Class<?>[] {Throwable.class}, wrapped));
    assertSame(
        root,
        invokeStatic(
            NettyOriginClient.class, "unwrap", new Class<?>[] {Throwable.class}, root));

    assertEquals(" ", StructuredLog.escape(" "));
    assertEquals(
        " ",
        invokeStatic(ResponseArtifacts.class, "json", new Class<?>[] {String.class}, " "));
  }

  @Test
  void hostValidationAcceptsBracketedIpv6WithoutPort() {
    var request =
        new io.netty.handler.codec.http.DefaultHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            io.netty.handler.codec.http.HttpMethod.GET,
            "/");
    request.headers().set(io.netty.handler.codec.http.HttpHeaderNames.HOST, "[::1]");
    assertEquals("[::1]", HttpRequestSecurity.validate(request, 0).host());
  }

  @Test
  void ambiguousTargetBoundaryPreservesFailureReason() {
    assertContract("#", "request target contains an ambiguous path character");
    assertContract("\\", "request target contains an ambiguous path character");
  }

  @Test
  void quotaRejectsZeroFileUnderflow() throws Exception {
    SpoolQuota quota = new SpoolQuota(8, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    Field files = SpoolQuota.class.getDeclaredField("files");
    files.setAccessible(true);
    files.setInt(quota, 0);
    try {
      assertThrows(IllegalStateException.class, reservation::close);
    } finally {
      quota.close();
    }
  }

  @Test
  void completedSpoolRejectsStreamAccountingUnderflow() throws Exception {
    Path path = temporaryDirectory.resolve("result.tmp");
    Files.write(path, new byte[0]);
    SpoolQuota quota = new SpoolQuota(8, 1);
    SpoolQuota.Reservation reservation = quota.openFile();
    StreamingSpool.Result result = new StreamingSpool.Result(path, 0, "digest", reservation);
    Method streamClosed = StreamingSpool.Result.class.getDeclaredMethod("streamClosed");
    streamClosed.setAccessible(true);
    try {
      InvocationTargetException failure =
          assertThrows(InvocationTargetException.class, () -> streamClosed.invoke(result));
      assertInstanceOf(IllegalStateException.class, failure.getCause());
    } finally {
      result.close();
      reservation.close();
      quota.close();
      Files.deleteIfExists(path);
    }
  }

  @Test
  void streamingSpoolClosesChannelAndUnwrapsCompletionFailure() throws Exception {
    SpoolQuota quota = new SpoolQuota(32, 1);
    StreamingSpool spool = new StreamingSpool(temporaryDirectory, "probe-", 32, quota);
    Method closeChannel = StreamingSpool.class.getDeclaredMethod("closeChannel");
    closeChannel.setAccessible(true);
    Field channelField = StreamingSpool.class.getDeclaredField("channel");
    channelField.setAccessible(true);
    try {
      FileChannel channel = (FileChannel) channelField.get(spool);
      assertTrue(channel.isOpen());
      assertNull(closeChannel.invoke(spool));
      assertFalse(channel.isOpen());

      IllegalStateException root = new IllegalStateException("root");
      CompletionException nested = new CompletionException(new CompletionException(root));
      CompletionException unwrapped =
          (CompletionException)
              invokeStatic(
                  StreamingSpool.class,
                  "asCompletionException",
                  new Class<?>[] {Throwable.class},
                  nested);
      assertSame(root, unwrapped.getCause());
    } finally {
      spool.close();
      quota.close();
    }
  }

  @Test
  void flightLeaseRejectsReferenceUnderflow() throws Exception {
    FlightLeaseRegistry registry = new FlightLeaseRegistry(new GatewayMetrics());
    CompletableFuture<Artifact> stage = new CompletableFuture<>();
    FlightLeaseRegistry.Lease lease = registry.acquire(stage);

    Field flightsField = FlightLeaseRegistry.class.getDeclaredField("flights");
    flightsField.setAccessible(true);
    Map<?, ?> flights = (Map<?, ?>) flightsField.get(registry);
    Object flight = flights.get(stage);
    Field references = flight.getClass().getDeclaredField("references");
    references.setAccessible(true);
    references.setInt(flight, 0);

    assertThrows(IllegalStateException.class, lease::close);
  }

  @Test
  void fileChannelProbeUsesTheRealFilesystem() throws Exception {
    Path path = temporaryDirectory.resolve("channel.tmp");
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      assertTrue(channel.isOpen());
    }
    assertTrue(Files.exists(path));
  }

  private static void assertContract(String target, String expectedMessage) {
    var request =
        new io.netty.handler.codec.http.DefaultHttpRequest(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            io.netty.handler.codec.http.HttpMethod.GET,
            target);
    request.headers().set(io.netty.handler.codec.http.HttpHeaderNames.HOST, "example.test");
    HttpRequestSecurity.HttpContractException failure =
        assertThrows(
            HttpRequestSecurity.HttpContractException.class,
            () -> HttpRequestSecurity.validate(request, 0));
    assertEquals(400, failure.status());
    assertEquals(expectedMessage, failure.getMessage());
  }

  private static Object invokeStatic(
      Class<?> owner, String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
    Method method = owner.getDeclaredMethod(name, parameterTypes);
    method.setAccessible(true);
    return method.invoke(null, arguments);
  }
}
