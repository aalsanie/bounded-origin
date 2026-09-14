package io.github.aalsanie.boundedorigin.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class SmokeClientMain {
  private SmokeClientMain() {}

  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    waitForReady(client);
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://gateway:8080/smoke"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 || !"tenacious-block\n".equals(response.body())) {
      throw new IllegalStateException(
          "unexpected gateway response: " + response.statusCode() + " " + response.body());
    }

    HttpResponse<String> metrics = get(client, "http://gateway:8081/metrics");
    if (metrics.statusCode() != 200
        || !metrics.body().contains("bounded_origin_origin_executions_total 1")) {
      throw new IllegalStateException("gateway metrics did not record the smoke origin execution");
    }
  }

  private static void waitForReady(HttpClient client) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 60; attempt++) {
      try {
        HttpResponse<String> response = get(client, "http://gateway:8081/ready");
        if (response.statusCode() == 200) {
          return;
        }
      } catch (Exception exception) {
        last = exception;
      }
      Thread.sleep(Duration.ofMillis(250));
    }
    throw new IllegalStateException("gateway did not become ready", last);
  }

  private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(2)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
