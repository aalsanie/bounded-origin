package io.github.aalsanie.boundedorigin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.Canonicalizers;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import io.github.aalsanie.boundedorigin.api.OriginPolicy;
import io.github.aalsanie.boundedorigin.core.PolicyEngine;
import io.github.aalsanie.boundedorigin.core.PolicyRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RepresentationIdentityNetworkTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({
    "legacy,500",
    "partial,500",
    "digest,500",
    "materialize,500",
    "artifact,500",
    "content-type,403",
    "content-encoding,403"
  })
  void unsafeProgrammaticPoliciesCannotReadArtifactsOrStartOriginWork(String defect, int status)
      throws Exception {
    ArtifactStore store =
        new ArtifactStore() {
          @Override
          public Optional<Artifact> get(OperationKey key) {
            throw new AssertionError("invalid representation must fail before artifact lookup");
          }

          @Override
          public void put(OperationKey key, Artifact artifact) {
            throw new AssertionError("invalid representation must never be published");
          }
        };
    try (TestOriginServer origin = new TestOriginServer()) {
      GatewayConfig config = GatewayTestFixtures.config(origin.port(), directory);
      var canonicalizer =
          "partial".equals(defect) || "legacy".equals(defect)
              ? Canonicalizers.byDimensions("target")
              : HttpOperation.canonicalizer();
      OriginPolicy policy =
          switch (defect) {
            case "materialize" ->
                OriginPolicy.materialize("test", 1, 100, "1", canonicalizer, config.globalBudget());
            case "artifact" -> OriginPolicy.artifactOnly("test", 1, 100, "1", canonicalizer);
            default ->
                OriginPolicy.boundedCompute(
                    "test", 1, 100, "1", canonicalizer, config.globalBudget());
          };
      var engine =
          new PolicyEngine(
              List.of(
                  new PolicyRule(
                      policy,
                      request -> {
                        if ("legacy".equals(defect)) {
                          return Optional.of(new Operation("http.request", request.attributes()));
                        }
                        String digest =
                            "digest".equals(defect)
                                ? "b".repeat(64)
                                : request.attributes().get("body-sha256").getFirst();
                        return Optional.of(
                            new HttpOperation(
                                    "POST",
                                    "example.test",
                                    "/public",
                                    digest,
                                    request.trustLevel(),
                                    RepresentationContract.PUBLIC,
                                    Map.of())
                                .operation());
                      })),
              OriginPolicy.deny("fallback", 1, 0));
      try (BoundedOriginGateway gateway = GatewayTestFixtures.start(config, engine, store);
          RawHttpClient caller = new RawHttpClient(gateway.listenAddress())) {
        Map<String, String> headers =
            defect.startsWith("content-")
                ? Map.of(
                    "Host",
                    "example.test",
                    defect,
                    "content-type".equals(defect) ? "text/plain" : "identity")
                : Map.of("Host", "example.test");
        assertEquals(
            status, caller.request("POST", "/public", headers, new byte[] {1, 2, 3}).status());
        assertEquals(0, origin.requests());
      }
    }
  }
}
