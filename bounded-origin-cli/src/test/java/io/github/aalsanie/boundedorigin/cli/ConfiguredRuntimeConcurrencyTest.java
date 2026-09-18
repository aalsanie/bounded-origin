package io.github.aalsanie.boundedorigin.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredRuntimeConcurrencyTest {
  @TempDir java.nio.file.Path temporaryDirectory;

  @Test
  void concurrentCloseIsIdempotentAndReleasesStoreLock() throws Exception {
    ConfigurationModel.RuntimeConfiguration configuration =
        ConfigurationTestSupport.runtimeConfiguration(temporaryDirectory);
    ConfiguredRuntime runtime = ConfiguredRuntime.assemble(configuration);
    int workers = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    List<Future<?>> futures = new ArrayList<>();

    try {
      for (int index = 0; index < workers; index++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  runtime.close();
                  return null;
                }));
      }

      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    assertFalse(runtime.isReady());
    try (ConfiguredRuntime replacement = ConfiguredRuntime.assemble(configuration)) {
      assertFalse(replacement.isReady());
    }
  }
}
