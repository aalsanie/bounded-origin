package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReachableProtocolBoundaryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void hostWithPortButEmptyRegisteredNameIsRejected() {
    HttpRequest request =
        new DefaultHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.GET,
            "/",
            DefaultHttpHeadersFactory.headersFactory().withValidation(false).newHeaders(),
            false);
    request.headers().set(HttpHeaderNames.HOST, ":80");

    HttpRequestSecurity.HttpContractException failure =
        assertThrows(
            HttpRequestSecurity.HttpContractException.class,
            () -> HttpRequestSecurity.validate(request, 0));

    assertEquals(400, failure.status());
  }

  @Test
  void malformedTargetWithQueryStillProducesBadRequest() throws Exception {
    GatewayConfig config =
        GatewayTestFixtures.config(GatewayTestFixtures.unusedPort(), temporaryDirectory);

    try (BoundedOriginGateway gateway =
        GatewayTestFixtures.start(
            config,
            GatewayTestFixtures.engine(GatewayTestFixtures.artifactOnlyPolicy()),
            new GatewayTestFixtures.MemoryArtifactStore())) {
      try (RawHttpClient client = new RawHttpClient(gateway.listenAddress())) {
        RawHttpClient.Response response =
            client.request(
                "GET",
                "/bad%zz?q=value",
                Map.of("Host", "example.test", "Connection", "close"),
                new byte[0]);

        assertEquals(400, response.status());
        assertTrue(response.bodyText().contains("malformed percent encoding"));
      }
    }
  }
}
