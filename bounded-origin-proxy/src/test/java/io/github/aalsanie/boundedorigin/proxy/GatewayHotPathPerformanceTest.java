package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GatewayHotPathPerformanceTest {
  @Test
  void validationAndHeaderSanitizationStayWithinRegressionBudget() {
    for (int index = 0; index < 2_000; index++) {
      exercise(index);
    }

    int iterations = 20_000;
    long started = System.nanoTime();
    for (int index = 0; index < iterations; index++) {
      exercise(index);
    }
    long elapsed = System.nanoTime() - started;
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsed);

    assertTrue(
        elapsed < TimeUnit.SECONDS.toNanos(5),
        "HTTP validation regression: " + iterations + " iterations took " + elapsedMillis + "ms");
  }

  private static void exercise(int index) {
    HttpRequest request =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/objects/" + index + "?view=compact");
    request.headers().set(HttpHeaderNames.HOST, "example.test");
    request.headers().set("X-App", "bounded-origin");
    request.headers().set("Forwarded", "for=203.0.113.1");
    HttpRequestSecurity.validate(request, 1024);
    HttpRequestSecurity.originRequestHeaders(request.headers(), 0, false);
  }
}
