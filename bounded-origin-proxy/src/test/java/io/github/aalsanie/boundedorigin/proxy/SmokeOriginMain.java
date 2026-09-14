package io.github.aalsanie.boundedorigin.proxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class SmokeOriginMain {
  private static final byte[] RESPONSE =
      ("HTTP/1.1 200 OK\r\n"
              + "Content-Type: text/plain\r\n"
              + "Content-Length: 16\r\n"
              + "Connection: keep-alive\r\n"
              + "\r\n"
              + "tenacious-block\n")
          .getBytes(StandardCharsets.US_ASCII);

  private SmokeOriginMain() {}

  public static void main(String[] args) throws Exception {
    try (ServerSocket server = new ServerSocket(9000, 64, InetAddress.getByName("0.0.0.0"))) {
      while (true) {
        Socket socket = server.accept();
        Thread.ofVirtual().start(() -> serve(socket));
      }
    }
  }

  private static void serve(Socket socket) {
    try (socket;
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
      while (readRequest(input)) {
        output.write(RESPONSE);
        output.flush();
      }
    } catch (IOException ignored) {
    }
  }

  private static boolean readRequest(BufferedInputStream input) throws IOException {
    int matched = 0;
    int value;
    while ((value = input.read()) >= 0) {
      if (value == "\r\n\r\n".charAt(matched)) {
        matched++;
        if (matched == 4) {
          return true;
        }
      } else {
        matched = value == '\r' ? 1 : 0;
      }
    }
    return false;
  }
}
