package io.github.aalsanie.boundedorigin.cli;

final class ConfigurationLimits {
  static final int MAX_BYTES = 128 * 1024;
  static final int MAX_CODE_POINTS = 128 * 1024;
  static final int MAX_DEPTH = 24;
  static final int MAX_SCALAR_CODE_POINTS = 8 * 1024;
  static final int MAX_COLLECTION_NODES = 4 * 1024;
  static final int MAX_TOTAL_NODES = 16 * 1024;
  static final int MAX_ROUTES = 1_024;
  static final int MAX_ROUTE_SEGMENTS = 128;
  static final int MAX_DIMENSIONS = 128;
  static final int MAX_PARAMETERS = 256;

  private ConfigurationLimits() {}
}
