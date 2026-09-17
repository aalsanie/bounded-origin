package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigurationTestSupport {
  private ConfigurationTestSupport() {}

  static String validYaml() {
    return """
    schema: 1
    gateway:
      origin.host: origin.internal
      origin.port: 8080
      temporary.directory: /tmp/bounded-origin
      ingress.trust: UNTRUSTED
      forwarded.trust: false
    store:
      directory: /var/lib/bounded-origin
      max-bytes: 1048576
      max-artifact-bytes: 262144
    routes:
      - id: render
        version: 1
        precedence: 100
        match:
          method: GET
          host: example.com
          path: /render/{id}
          trust: UNTRUSTED
        strategy: MATERIALIZE
        key:
          path: [id]
          query:
            include: [variant]
            order-independent: true
        materializer-version: v1
        budget:
          max-active: 4
          max-queued: 16
          max-execution-duration: PT10S
          max-result-bytes: 262144
      - id: client
        version: 2
        precedence: 90
        match:
          path: /client/{id}
        strategy: CLIENT_COMPUTE
        key:
          path: [id]
        materializer-version: client-v2
        client-computation:
          type: wasm
          version: v2
          parameters:
            mode: strict
    fallback:
      id: default-deny
      version: 1
      precedence: -2147483648
      strategy: DENY
    """;
  }

  static Path write(Path directory, String content) throws IOException {
    Path path = directory.resolve("config.yaml");
    Files.writeString(path, content, StandardCharsets.UTF_8);
    return path;
  }

  static String replace(String original, String target, String replacement) {
    if (!original.contains(target)) {
      throw new IllegalArgumentException("fixture does not contain target " + target);
    }
    return original.replace(target, replacement);
  }

  static ObjectFixture objectFixture() {
    Map<String, Object> gateway = new LinkedHashMap<>();
    gateway.put("origin.host", "origin.internal");
    gateway.put("origin.port", 8080);
    gateway.put("temporary.directory", "/tmp/bounded-origin");
    gateway.put("ingress.trust", "UNTRUSTED");
    gateway.put("forwarded.trust", false);

    Map<String, Object> store = new LinkedHashMap<>();
    store.put("directory", "/var/lib/bounded-origin");
    store.put("max-bytes", 1_048_576L);
    store.put("max-artifact-bytes", 262_144L);

    Map<String, Object> renderMatch = new LinkedHashMap<>();
    renderMatch.put("method", "GET");
    renderMatch.put("host", "example.com");
    renderMatch.put("path", "/render/{id}");
    renderMatch.put("trust", "UNTRUSTED");

    Map<String, Object> renderQuery = new LinkedHashMap<>();
    renderQuery.put("include", new ArrayList<>(List.of("variant")));
    renderQuery.put("order-independent", true);

    Map<String, Object> renderKey = new LinkedHashMap<>();
    renderKey.put("path", new ArrayList<>(List.of("id")));
    renderKey.put("query", renderQuery);

    Map<String, Object> renderBudget = new LinkedHashMap<>();
    renderBudget.put("max-active", 4);
    renderBudget.put("max-queued", 16);
    renderBudget.put("max-execution-duration", "PT10S");
    renderBudget.put("max-result-bytes", 262_144L);

    Map<String, Object> render = new LinkedHashMap<>();
    render.put("id", "render");
    render.put("version", 1L);
    render.put("precedence", 100);
    render.put("match", renderMatch);
    render.put("strategy", "MATERIALIZE");
    render.put("key", renderKey);
    render.put("materializer-version", "v1");
    render.put("budget", renderBudget);

    Map<String, Object> clientMatch = new LinkedHashMap<>();
    clientMatch.put("path", "/client/{id}");

    Map<String, Object> clientKey = new LinkedHashMap<>();
    clientKey.put("path", new ArrayList<>(List.of("id")));

    Map<String, Object> clientParameters = new LinkedHashMap<>();
    clientParameters.put("mode", "strict");

    Map<String, Object> clientComputation = new LinkedHashMap<>();
    clientComputation.put("type", "wasm");
    clientComputation.put("version", "v2");
    clientComputation.put("parameters", clientParameters);

    Map<String, Object> client = new LinkedHashMap<>();
    client.put("id", "client");
    client.put("version", 2L);
    client.put("precedence", 90);
    client.put("match", clientMatch);
    client.put("strategy", "CLIENT_COMPUTE");
    client.put("key", clientKey);
    client.put("materializer-version", "client-v2");
    client.put("client-computation", clientComputation);

    List<Object> routes = new ArrayList<>();
    routes.add(render);
    routes.add(client);

    Map<String, Object> fallback = new LinkedHashMap<>();
    fallback.put("id", "default-deny");
    fallback.put("version", 1L);
    fallback.put("precedence", Integer.MIN_VALUE);
    fallback.put("strategy", "DENY");

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", 1);
    root.put("gateway", gateway);
    root.put("store", store);
    root.put("routes", routes);
    root.put("fallback", fallback);

    return new ObjectFixture(
        root,
        gateway,
        store,
        routes,
        render,
        renderMatch,
        renderKey,
        renderQuery,
        renderBudget,
        client,
        clientMatch,
        clientComputation,
        clientParameters,
        fallback);
  }

  record ObjectFixture(
      Map<String, Object> root,
      Map<String, Object> gateway,
      Map<String, Object> store,
      List<Object> routes,
      Map<String, Object> render,
      Map<String, Object> renderMatch,
      Map<String, Object> renderKey,
      Map<String, Object> renderQuery,
      Map<String, Object> renderBudget,
      Map<String, Object> client,
      Map<String, Object> clientMatch,
      Map<String, Object> clientComputation,
      Map<String, Object> clientParameters,
      Map<String, Object> fallback) {}
}
