package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayConfigAdditionalBoundaryTest {
  @Test
  void optionalListenAndAdminHostsStillRejectBlankOrControlValues() {
    Map<String, String> blankListen = baseValues();
    blankListen.put("listen.host", " ");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(blankListen));

    Map<String, String> blankAdmin = baseValues();
    blankAdmin.put("admin.host", "\t");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(blankAdmin));

    Map<String, String> controlAdmin = baseValues();
    controlAdmin.put("admin.host", "bad\u0001host");
    assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(controlAdmin));
  }

  private static Map<String, String> baseValues() {
    Map<String, String> values = new HashMap<>();
    values.put("origin.host", "127.0.0.1");
    values.put("origin.port", "8080");
    values.put("temporary.directory", Path.of("build", "gateway-config-additional").toString());
    return values;
  }
}
