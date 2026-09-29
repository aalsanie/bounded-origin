package consumer;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.Operation;
import io.github.aalsanie.boundedorigin.core.BoundedOriginExecutor;
import io.github.aalsanie.boundedorigin.proxy.GatewayConfig;
import io.github.aalsanie.boundedorigin.store.fs.FileSystemArtifactStore;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ConsumerSmoke {
  private ConsumerSmoke() {}

  public static void main(String[] args) throws Exception {
    Operation operation = new Operation("consumer-smoke", Map.of("id", List.of("42")));
    Budget budget = new Budget(1, 0, Duration.ofSeconds(1), 1024);

    if (!"consumer-smoke".equals(operation.type()) || budget.maxActive() != 1) {
      throw new IllegalStateException("public API values did not round-trip");
    }

    try (BoundedOriginExecutor ignored =
            new BoundedOriginExecutor(budget, Duration.ZERO, 16);
        FileSystemArtifactStore store =
            new FileSystemArtifactStore(Files.createTempDirectory("bounded-origin-consumer"), 4096, 16)) {
      if (store.stats().entryCount() != 0) {
        throw new IllegalStateException("fresh filesystem store was not empty");
      }
    }

    if (GatewayConfig.class.getName().isBlank()) {
      throw new IllegalStateException("proxy artifact did not resolve");
    }

    System.out.println("bounded-origin external consumer ok");
  }
}
