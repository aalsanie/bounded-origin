package io.github.aalsanie.boundedorigin.store.fs;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

@FunctionalInterface
interface TempOutputFactory {
  OutputStream open(Path path) throws IOException;
}
