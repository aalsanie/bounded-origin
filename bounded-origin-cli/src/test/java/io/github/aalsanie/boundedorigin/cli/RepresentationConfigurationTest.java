package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aalsanie.boundedorigin.api.OriginDecision;
import io.github.aalsanie.boundedorigin.api.RequestDescriptor;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.proxy.HttpOperation;
import io.github.aalsanie.boundedorigin.proxy.RepresentationContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RepresentationConfigurationTest {
  @Test
  void captureValuesCannotBeReinterpretedAsTemplatePlaceholders() throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.renderMatch().put("path", "/render/{id}/{variant}");
    fixture.renderKey().put("path", List.of("id", "variant"));
    PolicyEngine engine = compile(fixture);
    var literal = select(engine, "/render/{variant}/b", "", Map.of());
    var different = select(engine, "/render/b/b", "", Map.of());
    assertEquals("/render/{variant}/b", HttpOperation.from(literal.operation()).target());
    assertNotEquals(literal.operationKey(), different.operationKey());
  }

  @Test
  void requiresAnExplicitAudienceAndPersistentLifetime() throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.render().remove("representation");
    assertThrows(ConfigurationException.class, () -> compile(fixture));
    fixture.render().put("representation", "PUBLIC");
    assertThrows(ConfigurationException.class, () -> compile(fixture));
    fixture.render().put("strategy", "ARTIFACT_ONLY");
    fixture.render().remove("budget");
    assertThrows(ConfigurationException.class, () -> compile(fixture));
    fixture.render().put("representation", "PUBLIC_IMMUTABLE");
    assertEquals(
        RepresentationContract.PUBLIC_IMMUTABLE,
        HttpOperation.from(select(compile(fixture), "/render/a", "", Map.of()).operation())
            .representation());
    fixture.render().put("representation", "unknown");
    assertThrows(ConfigurationException.class, () -> compile(fixture));
    fixture.render().put("representation", true);
    assertThrows(ConfigurationException.class, () -> compile(fixture));
  }

  @Test
  void publicTransientOperationsDoNotPromisePersistentReuse() throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.render().put("strategy", "BOUNDED_COMPUTE");
    fixture.render().put("representation", "PUBLIC");
    assertEquals(
        RepresentationContract.PUBLIC,
        HttpOperation.from(select(compile(fixture), "/render/a", "", Map.of()).operation())
            .representation());
    fixture.client().put("representation", "PUBLIC");
    assertThrows(ConfigurationException.class, () -> compile(fixture));
    fixture.client().remove("representation");
    fixture.render().put("strategy", "DENY");
    assertThrows(ConfigurationException.class, () -> compile(fixture));
  }

  @Test
  void canonicalProducerAndIdentityHaveExactlyTheSameSelectedInputs()
      throws ConfigurationException {
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.renderKey().put("headers", List.of("Accept-Language"));
    PolicyEngine engine = compile(fixture);
    var encoded =
        select(
            engine,
            "/render/%41",
            "noise=secret&%76ariant=%7e&variant=&variant&variant=a+b",
            Map.of(
                "header:accept-language",
                List.of("en", "fr"),
                "header:x-account",
                List.of("alice")));
    var canonical =
        select(
            engine,
            "/render/A",
            "variant=a+b&variant&variant=~&variant=&noise=other",
            Map.of(
                "header:accept-language", List.of("en", "fr"), "header:x-account", List.of("bob")));
    assertEquals(encoded.operationKey(), canonical.operationKey());
    assertEquals(encoded.operation(), canonical.operation());
    HttpOperation producer = HttpOperation.from(encoded.operation());
    assertEquals("/render/A?variant&variant=&variant=a+b&variant=~", producer.target());
    assertEquals(Map.of("accept-language", List.of("en", "fr")), producer.headers());
    var otherLanguage =
        select(
            engine,
            "/render/A",
            "variant=a+b&variant&variant=~&variant=",
            Map.of("header:accept-language", List.of("fr", "en")));
    assertNotEquals(canonical.operationKey(), otherLanguage.operationKey());
    var absent = select(engine, "/render/A", "", Map.of());
    var empty = select(engine, "/render/A", "", Map.of("header:accept-language", List.of("")));
    assertNotEquals(absent.operationKey(), empty.operationKey());
    assertEquals(
        List.of(), HttpOperation.from(absent.operation()).headers().get("accept-language"));
    assertEquals(
        List.of(""), HttpOperation.from(empty.operation()).headers().get("accept-language"));
  }

  @Test
  void validatesHeaderSelectorsWithTheSameStrictBoundariesAsOtherDimensions() {
    for (Object headers :
        List.of(
            "accept",
            List.of("accept", "Accept"),
            List.of("authorization"),
            List.of("cookie"),
            List.of("range"),
            List.of("if-match"),
            List.of("bad name"),
            List.of(1),
            IntStream.rangeClosed(0, ConfigurationLimits.MAX_DIMENSIONS)
                .mapToObj(index -> "x-" + index)
                .toList())) {
      var fixture = ConfigurationTestSupport.objectFixture();
      fixture.renderKey().put("headers", headers);
      assertThrows(ConfigurationException.class, () -> compile(fixture), headers.toString());
    }
    var fixture = ConfigurationTestSupport.objectFixture();
    fixture.client().put("key", Map.of("path", List.of("id"), "headers", List.of("accept")));
    assertThrows(ConfigurationException.class, () -> compile(fixture));
  }

  private static PolicyEngine compile(ConfigurationTestSupport.ObjectFixture fixture)
      throws ConfigurationException {
    var configuration = ConfigurationDecoder.decode(fixture.root());
    return PolicyConfigurationCompiler.compile(
        configuration, GatewayConfig.from(configuration.gateway()).globalBudget());
  }

  private static OriginDecision.Selected select(
      PolicyEngine engine, String path, String query, Map<String, List<String>> headers) {
    Map<String, List<String>> values = new LinkedHashMap<>(headers);
    values.put("method", List.of("GET"));
    values.put("host", List.of("example.com"));
    values.put("path", List.of(path));
    values.put("query", List.of(query));
    values.put("body-sha256", List.of("a".repeat(64)));
    return assertInstanceOf(
        OriginDecision.Selected.class,
        engine.evaluate(new RequestDescriptor("http.request", values, TrustLevel.UNTRUSTED)));
  }
}
