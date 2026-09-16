package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ConfigurationTestSupport {
  static final String VALID =
      """
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

  private ConfigurationTestSupport() {}

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
}
