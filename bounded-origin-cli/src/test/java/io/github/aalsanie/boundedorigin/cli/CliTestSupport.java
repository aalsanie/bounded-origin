package io.github.aalsanie.boundedorigin.cli;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

final class CliTestSupport {
  private CliTestSupport() {}

  static Path writeRuntimeConfiguration(Path directory, int listenPort, int adminPort)
      throws IOException {
    return ConfigurationTestSupport.write(directory, runtimeYaml(directory, listenPort, adminPort));
  }

  static String runtimeYaml(Path directory, int listenPort, int adminPort) {
    String temporary =
        yamlScalar(directory.resolve("spool").toAbsolutePath().normalize().toString());
    String store = yamlScalar(directory.resolve("store").toAbsolutePath().normalize().toString());
    String gatewayPrefix =
        "gateway:\n"
            + "  listen.host: 127.0.0.1\n"
            + "  listen.port: "
            + listenPort
            + "\n"
            + "  admin.host: 127.0.0.1\n"
            + "  admin.port: "
            + adminPort
            + "\n";

    return ConfigurationTestSupport.validYaml()
        .replace("gateway:\n", gatewayPrefix)
        .replace("origin.host: origin.internal", "origin.host: 127.0.0.1")
        .replace("origin.port: 8080", "origin.port: 65534")
        .replace("temporary.directory: /tmp/bounded-origin", "temporary.directory: " + temporary)
        .replace("directory: /var/lib/bounded-origin", "directory: " + store);
  }

  static int freePort() throws IOException {
    try (ServerSocket socket = bindLoopback(0)) {
      return socket.getLocalPort();
    }
  }

  static ServerSocket bindLoopback(int port) throws IOException {
    return new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
  }

  private static String yamlScalar(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
