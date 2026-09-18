package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class ZeroCodeTestConfiguration {
  private ZeroCodeTestConfiguration() {}

  static Path write(
      Path directory, int listenPort, int adminPort, int originPort, boolean denyHealth)
      throws IOException {
    Path path = directory.resolve("zero-code.yaml");
    Files.writeString(
        path, yaml(directory, listenPort, adminPort, originPort, denyHealth), StandardCharsets.UTF_8);
    return path;
  }

  private static String yaml(
      Path directory, int listenPort, int adminPort, int originPort, boolean denyHealth) {
    String temporary =
        yamlScalar(directory.resolve("spool").toAbsolutePath().normalize().toString());
    String store = yamlScalar(directory.resolve("store").toAbsolutePath().normalize().toString());
    String health =
        denyHealth
            ? """
              - id: health
                version: 1
                precedence: 70
                match:
                  method: GET
                  path: /health
                  trust: UNTRUSTED
                strategy: DENY
            """
            : """
              - id: health
                version: 1
                precedence: 70
                match:
                  method: GET
                  path: /health
                  trust: UNTRUSTED
                strategy: BOUNDED_COMPUTE
                key:
                  path: []
                  query:
                    include: []
                    order-independent: true
                materializer-version: health-v1
                budget:
                  max-active: 2
                  max-queued: 2
                  max-execution-duration: PT5S
                  max-result-bytes: 65536
            """;

    return """
        schema: 1
        gateway:
          listen.host: 127.0.0.1
          listen.port: %d
          admin.host: 127.0.0.1
          admin.port: %d
          origin.host: 127.0.0.1
          origin.port: %d
          temporary.directory: %s
          ingress.trust: UNTRUSTED
          forwarded.trust: false
          http.max-request-body-bytes: 65536
          spool.max-bytes: 1048576
          spool.max-files: 128
          origin.max-connections: 2
          origin.max-pending-acquires: 2
          origin.acquire-timeout: PT2S
          origin.max-active: 2
          origin.max-queued: 2
          origin.max-execution-duration: PT5S
          origin.max-result-bytes: 65536
          origin.connect-timeout: PT2S
          origin.response-timeout: PT5S
          request.timeout: PT10S
          drain.timeout: PT5S
        store:
          directory: %s
          max-bytes: 4194304
          max-artifact-bytes: 65536
        routes:
          - id: render
            version: 1
            precedence: 100
            match:
              method: GET
              path: /render/{id}
              trust: UNTRUSTED
            strategy: MATERIALIZE
            key:
              path: [id]
              query:
                include: [variant]
                order-independent: true
            materializer-version: render-v1
            budget:
              max-active: 2
              max-queued: 2
              max-execution-duration: PT5S
              max-result-bytes: 65536
          - id: unsafe-allowed
            version: 1
            precedence: 90
            match:
              method: GET
              path: /unsafe/allowed
              trust: UNTRUSTED
            strategy: BOUNDED_COMPUTE
            key:
              path: []
              query:
                include: []
                order-independent: true
            materializer-version: unsafe-allowed-v1
            budget:
              max-active: 1
              max-queued: 1
              max-execution-duration: PT5S
              max-result-bytes: 65536
          - id: unsafe-deny
            version: 1
            precedence: 80
            match:
              method: GET
              path: /unsafe/**
              trust: UNTRUSTED
            strategy: DENY
        %s
        fallback:
          id: default-deny
          version: 1
          precedence: -2147483648
          strategy: DENY
        """
        .formatted(listenPort, adminPort, originPort, temporary, store, health);
  }

  private static String yamlScalar(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
