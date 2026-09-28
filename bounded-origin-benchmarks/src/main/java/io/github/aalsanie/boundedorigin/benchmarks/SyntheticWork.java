package io.github.aalsanie.boundedorigin.benchmarks;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SyntheticWork {
  private SyntheticWork() {}

  static String identity(URI target) {
    List<String> selected = new ArrayList<>();
    String query = target.getRawQuery();
    if (query != null) {
      for (String pair : query.split("&", -1)) {
        int equals = pair.indexOf('=');
        String name = equals < 0 ? pair : pair.substring(0, equals);
        if (decode(name).equals("q")) {
          selected.add(equals < 0 ? "bare" : "value:" + decode(pair.substring(equals + 1)));
        }
      }
    }
    Collections.sort(selected);
    StringBuilder key = new StringBuilder(target.getPath());
    for (String value : selected) {
      key.append('|').append(value.length()).append(':').append(value);
    }
    return key.toString();
  }

  private static String decode(String value) {
    return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  static byte[] compute(String identity, long iterations, int bytes) {
    long value = 0x6a09e667f3bcc909L;
    for (int index = 0; index < identity.length(); index++) {
      value = Long.rotateLeft(value ^ identity.charAt(index), 13) * 0x9e3779b97f4a7c15L;
    }
    for (long index = 0; index < iterations; index++) {
      value = Long.rotateLeft(value ^ index, 17) * 0x94d049bb133111ebL;
    }
    byte[] pattern =
        (identity + "\n" + Long.toUnsignedString(value, 16) + "\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[bytes];
    for (int index = 0; index < bytes; index++) {
      body[index] = pattern[index % pattern.length];
    }
    return body;
  }
}
