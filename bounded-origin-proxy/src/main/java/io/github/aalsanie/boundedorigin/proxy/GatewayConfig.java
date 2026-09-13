package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.Budget;
import io.github.aalsanie.boundedorigin.api.TrustLevel;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GatewayConfig {
  private static final Set<String> KEYS =
      Set.of(
          "listen.host",
          "listen.port",
          "admin.host",
          "admin.port",
          "origin.host",
          "origin.port",
          "temporary.directory",
          "ingress.trust",
          "forwarded.trust",
          "event-loop.threads",
          "origin.event-loop.threads",
          "frontend.max-connections",
          "server.backlog",
          "http.max-initial-line-bytes",
          "http.max-header-bytes",
          "http.chunk-bytes",
          "http.max-request-body-bytes",
          "http.chunked-response-threshold-bytes",
          "spool.max-bytes",
          "spool.max-files",
          "origin.max-connections",
          "origin.max-pending-acquires",
          "origin.acquire-timeout",
          "origin.max-active",
          "origin.max-queued",
          "origin.max-execution-duration",
          "origin.max-result-bytes",
          "origin.connect-timeout",
          "origin.response-timeout",
          "origin.failure-cooldown",
          "origin.max-cooldown-entries",
          "request.timeout",
          "idle.timeout",
          "drain.timeout",
          "overload.retry-after-seconds",
          "downstream.write-low-watermark-bytes",
          "downstream.write-high-watermark-bytes");

  private final InetSocketAddress listenAddress;
  private final InetSocketAddress adminAddress;
  private final InetSocketAddress originAddress;
  private final Path temporaryDirectory;
  private final Budget globalBudget;
  private final Duration failureCooldown;
  private final int maxCooldownEntries;
  private final int eventLoopThreads;
  private final int originEventLoopThreads;
  private final int maxClientConnections;
  private final int serverBacklog;
  private final int maxInitialLineLength;
  private final int maxHeaderSize;
  private final int maxChunkSize;
  private final long maxRequestBodyBytes;
  private final long maxSpoolBytes;
  private final int maxSpoolFiles;
  private final int originMaxConnections;
  private final int originMaxPendingAcquires;
  private final Duration originAcquireTimeout;
  private final Duration requestTimeout;
  private final Duration originConnectTimeout;
  private final Duration originResponseTimeout;
  private final Duration idleTimeout;
  private final Duration drainTimeout;
  private final int writeBufferLowWaterMark;
  private final int writeBufferHighWaterMark;
  private final int retryAfterSeconds;
  private final TrustLevel ingressTrustLevel;
  private final boolean trustForwardedHeaders;
  private final long chunkedResponseThresholdBytes;

  private GatewayConfig(Builder builder) {
    listenAddress = requireListenAddress(builder.listenAddress, "listenAddress");
    adminAddress = requireListenAddress(builder.adminAddress, "adminAddress");
    if (listenAddress.equals(adminAddress) && listenAddress.getPort() != 0) {
      throw new IllegalArgumentException("listenAddress and adminAddress must be distinct");
    }
    originAddress = requireOriginAddress(builder.originAddress);
    temporaryDirectory =
        Objects.requireNonNull(builder.temporaryDirectory, "temporaryDirectory")
            .toAbsolutePath()
            .normalize();
    globalBudget = Objects.requireNonNull(builder.globalBudget, "globalBudget");
    failureCooldown = requirePositive(builder.failureCooldown, "failureCooldown");
    maxCooldownEntries = requirePositive(builder.maxCooldownEntries, "maxCooldownEntries");
    eventLoopThreads = requirePositive(builder.eventLoopThreads, "eventLoopThreads");
    originEventLoopThreads =
        requirePositive(builder.originEventLoopThreads, "originEventLoopThreads");
    maxClientConnections = requirePositive(builder.maxClientConnections, "maxClientConnections");
    serverBacklog = requirePositive(builder.serverBacklog, "serverBacklog");
    maxInitialLineLength = requirePositive(builder.maxInitialLineLength, "maxInitialLineLength");
    maxHeaderSize = requirePositive(builder.maxHeaderSize, "maxHeaderSize");
    maxChunkSize = requirePositive(builder.maxChunkSize, "maxChunkSize");
    if (builder.maxRequestBodyBytes < 0) {
      throw new IllegalArgumentException("maxRequestBodyBytes must be non-negative");
    }
    maxRequestBodyBytes = builder.maxRequestBodyBytes;
    if (builder.maxSpoolBytes <= 0) {
      throw new IllegalArgumentException("maxSpoolBytes must be positive");
    }
    maxSpoolBytes = builder.maxSpoolBytes;
    maxSpoolFiles = requirePositive(builder.maxSpoolFiles, "maxSpoolFiles");
    originMaxConnections = requirePositive(builder.originMaxConnections, "originMaxConnections");
    if (builder.originMaxPendingAcquires < 0) {
      throw new IllegalArgumentException("originMaxPendingAcquires must be non-negative");
    }
    originMaxPendingAcquires = builder.originMaxPendingAcquires;
    originAcquireTimeout = requirePositive(builder.originAcquireTimeout, "originAcquireTimeout");
    requestTimeout = requirePositive(builder.requestTimeout, "requestTimeout");
    originConnectTimeout = requirePositive(builder.originConnectTimeout, "originConnectTimeout");
    originResponseTimeout = requirePositive(builder.originResponseTimeout, "originResponseTimeout");
    idleTimeout = requirePositive(builder.idleTimeout, "idleTimeout");
    drainTimeout = requirePositive(builder.drainTimeout, "drainTimeout");
    writeBufferLowWaterMark =
        requirePositive(builder.writeBufferLowWaterMark, "writeBufferLowWaterMark");
    writeBufferHighWaterMark =
        requirePositive(builder.writeBufferHighWaterMark, "writeBufferHighWaterMark");
    if (writeBufferHighWaterMark <= writeBufferLowWaterMark) {
      throw new IllegalArgumentException(
          "writeBufferHighWaterMark must exceed writeBufferLowWaterMark");
    }
    retryAfterSeconds = requirePositive(builder.retryAfterSeconds, "retryAfterSeconds");
    ingressTrustLevel = Objects.requireNonNull(builder.ingressTrustLevel, "ingressTrustLevel");
    trustForwardedHeaders = builder.trustForwardedHeaders;
    if (builder.chunkedResponseThresholdBytes < 0) {
      throw new IllegalArgumentException("chunkedResponseThresholdBytes must be non-negative");
    }
    chunkedResponseThresholdBytes = builder.chunkedResponseThresholdBytes;
    if (maxSpoolBytes < Math.max(maxRequestBodyBytes, globalBudget.maxResultBytes())) {
      throw new IllegalArgumentException(
          "maxSpoolBytes must fit the largest configured request or result");
    }
    if (originResponseTimeout.compareTo(requestTimeout) > 0) {
      throw new IllegalArgumentException("originResponseTimeout must not exceed requestTimeout");
    }
    if (globalBudget.timeout().compareTo(requestTimeout) > 0) {
      throw new IllegalArgumentException("global budget timeout must not exceed requestTimeout");
    }
  }

  public static GatewayConfig defaults(
      InetSocketAddress listenAddress,
      InetSocketAddress adminAddress,
      InetSocketAddress originAddress,
      Path temporaryDirectory,
      Budget globalBudget) {
    Objects.requireNonNull(globalBudget, "globalBudget");
    int processors = Runtime.getRuntime().availableProcessors();
    int eventLoops = Math.max(2, Math.min(16, processors));
    long largestSpool = Math.max(8L * 1024 * 1024, globalBudget.maxResultBytes());
    return builder()
        .listenAddress(listenAddress)
        .adminAddress(adminAddress)
        .originAddress(originAddress)
        .temporaryDirectory(temporaryDirectory)
        .globalBudget(globalBudget)
        .failureCooldown(Duration.ofSeconds(2))
        .maxCooldownEntries(4_096)
        .eventLoopThreads(eventLoops)
        .originEventLoopThreads(Math.max(2, Math.min(8, processors)))
        .maxClientConnections(4_096)
        .serverBacklog(1_024)
        .maxInitialLineLength(8_192)
        .maxHeaderSize(16_384)
        .maxChunkSize(16_384)
        .maxRequestBodyBytes(8L * 1024 * 1024)
        .maxSpoolBytes(Math.multiplyExact(largestSpool, 32))
        .maxSpoolFiles(4_096)
        .originMaxConnections(Math.max(1, globalBudget.maxActive()))
        .originMaxPendingAcquires(globalBudget.maxQueued())
        .originAcquireTimeout(Duration.ofSeconds(2))
        .requestTimeout(Duration.ofSeconds(30))
        .originConnectTimeout(Duration.ofSeconds(3))
        .originResponseTimeout(minimum(Duration.ofSeconds(20), globalBudget.timeout()))
        .idleTimeout(Duration.ofSeconds(30))
        .drainTimeout(Duration.ofSeconds(30))
        .writeBufferLowWaterMark(32 * 1024)
        .writeBufferHighWaterMark(64 * 1024)
        .retryAfterSeconds(1)
        .ingressTrustLevel(TrustLevel.UNTRUSTED)
        .trustForwardedHeaders(false)
        .chunkedResponseThresholdBytes(64 * 1024)
        .build();
  }

  public static GatewayConfig from(Map<String, String> values) {
    Objects.requireNonNull(values, "values");
    Map<String, String> copy = Map.copyOf(values);
    rejectUnknown(copy);
    String originHost = required(copy, "origin.host");
    int originPort = port(required(copy, "origin.port"), "origin.port", false);
    String temporary = required(copy, "temporary.directory");
    Budget budget =
        new Budget(
            positiveInt(copy.getOrDefault("origin.max-active", "8"), "origin.max-active"),
            nonNegativeInt(copy.getOrDefault("origin.max-queued", "64"), "origin.max-queued"),
            positiveDuration(
                copy.getOrDefault("origin.max-execution-duration", "PT20S"),
                "origin.max-execution-duration"),
            positiveLong(
                copy.getOrDefault("origin.max-result-bytes", "16777216"),
                "origin.max-result-bytes"));
    int processors = Runtime.getRuntime().availableProcessors();
    return builder()
        .listenAddress(
            address(
                copy.getOrDefault("listen.host", "0.0.0.0"),
                port(copy.getOrDefault("listen.port", "8080"), "listen.port", true),
                "listen.host"))
        .adminAddress(
            address(
                copy.getOrDefault("admin.host", "127.0.0.1"),
                port(copy.getOrDefault("admin.port", "8081"), "admin.port", true),
                "admin.host"))
        .originAddress(address(originHost, originPort, "origin.host"))
        .temporaryDirectory(Path.of(temporary))
        .globalBudget(budget)
        .failureCooldown(
            positiveDuration(
                copy.getOrDefault("origin.failure-cooldown", "PT2S"), "origin.failure-cooldown"))
        .maxCooldownEntries(
            positiveInt(
                copy.getOrDefault("origin.max-cooldown-entries", "4096"),
                "origin.max-cooldown-entries"))
        .eventLoopThreads(
            positiveInt(
                copy.getOrDefault(
                    "event-loop.threads", Integer.toString(Math.max(2, Math.min(16, processors)))),
                "event-loop.threads"))
        .originEventLoopThreads(
            positiveInt(
                copy.getOrDefault(
                    "origin.event-loop.threads",
                    Integer.toString(Math.max(2, Math.min(8, processors)))),
                "origin.event-loop.threads"))
        .maxClientConnections(
            positiveInt(
                copy.getOrDefault("frontend.max-connections", "4096"), "frontend.max-connections"))
        .serverBacklog(positiveInt(copy.getOrDefault("server.backlog", "1024"), "server.backlog"))
        .maxInitialLineLength(
            positiveInt(
                copy.getOrDefault("http.max-initial-line-bytes", "8192"),
                "http.max-initial-line-bytes"))
        .maxHeaderSize(
            positiveInt(
                copy.getOrDefault("http.max-header-bytes", "16384"), "http.max-header-bytes"))
        .maxChunkSize(
            positiveInt(copy.getOrDefault("http.chunk-bytes", "16384"), "http.chunk-bytes"))
        .maxRequestBodyBytes(
            nonNegativeLong(
                copy.getOrDefault("http.max-request-body-bytes", "8388608"),
                "http.max-request-body-bytes"))
        .maxSpoolBytes(
            positiveLong(copy.getOrDefault("spool.max-bytes", "536870912"), "spool.max-bytes"))
        .maxSpoolFiles(positiveInt(copy.getOrDefault("spool.max-files", "4096"), "spool.max-files"))
        .originMaxConnections(
            positiveInt(
                copy.getOrDefault("origin.max-connections", Integer.toString(budget.maxActive())),
                "origin.max-connections"))
        .originMaxPendingAcquires(
            nonNegativeInt(
                copy.getOrDefault(
                    "origin.max-pending-acquires", Integer.toString(budget.maxQueued())),
                "origin.max-pending-acquires"))
        .originAcquireTimeout(
            positiveDuration(
                copy.getOrDefault("origin.acquire-timeout", "PT2S"), "origin.acquire-timeout"))
        .requestTimeout(
            positiveDuration(copy.getOrDefault("request.timeout", "PT30S"), "request.timeout"))
        .originConnectTimeout(
            positiveDuration(
                copy.getOrDefault("origin.connect-timeout", "PT3S"), "origin.connect-timeout"))
        .originResponseTimeout(
            positiveDuration(
                copy.getOrDefault("origin.response-timeout", "PT20S"), "origin.response-timeout"))
        .idleTimeout(positiveDuration(copy.getOrDefault("idle.timeout", "PT30S"), "idle.timeout"))
        .drainTimeout(
            positiveDuration(copy.getOrDefault("drain.timeout", "PT30S"), "drain.timeout"))
        .writeBufferLowWaterMark(
            positiveInt(
                copy.getOrDefault("downstream.write-low-watermark-bytes", "32768"),
                "downstream.write-low-watermark-bytes"))
        .writeBufferHighWaterMark(
            positiveInt(
                copy.getOrDefault("downstream.write-high-watermark-bytes", "65536"),
                "downstream.write-high-watermark-bytes"))
        .retryAfterSeconds(
            positiveInt(
                copy.getOrDefault("overload.retry-after-seconds", "1"),
                "overload.retry-after-seconds"))
        .ingressTrustLevel(trust(copy.getOrDefault("ingress.trust", TrustLevel.UNTRUSTED.name())))
        .trustForwardedHeaders(
            booleanValue(copy.getOrDefault("forwarded.trust", "false"), "forwarded.trust"))
        .chunkedResponseThresholdBytes(
            nonNegativeLong(
                copy.getOrDefault("http.chunked-response-threshold-bytes", "65536"),
                "http.chunked-response-threshold-bytes"))
        .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public InetSocketAddress listenAddress() {
    return listenAddress;
  }

  public InetSocketAddress adminAddress() {
    return adminAddress;
  }

  public InetSocketAddress originAddress() {
    return originAddress;
  }

  public Path temporaryDirectory() {
    return temporaryDirectory;
  }

  public Budget globalBudget() {
    return globalBudget;
  }

  public Duration failureCooldown() {
    return failureCooldown;
  }

  public int maxCooldownEntries() {
    return maxCooldownEntries;
  }

  public int eventLoopThreads() {
    return eventLoopThreads;
  }

  public int originEventLoopThreads() {
    return originEventLoopThreads;
  }

  public int maxClientConnections() {
    return maxClientConnections;
  }

  public int serverBacklog() {
    return serverBacklog;
  }

  public int maxInitialLineLength() {
    return maxInitialLineLength;
  }

  public int maxHeaderSize() {
    return maxHeaderSize;
  }

  public int maxChunkSize() {
    return maxChunkSize;
  }

  public long maxRequestBodyBytes() {
    return maxRequestBodyBytes;
  }

  public long maxSpoolBytes() {
    return maxSpoolBytes;
  }

  public int maxSpoolFiles() {
    return maxSpoolFiles;
  }

  public int originMaxConnections() {
    return originMaxConnections;
  }

  public int originMaxPendingAcquires() {
    return originMaxPendingAcquires;
  }

  public Duration originAcquireTimeout() {
    return originAcquireTimeout;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public Duration originConnectTimeout() {
    return originConnectTimeout;
  }

  public Duration originResponseTimeout() {
    return originResponseTimeout;
  }

  public Duration idleTimeout() {
    return idleTimeout;
  }

  public Duration drainTimeout() {
    return drainTimeout;
  }

  public int writeBufferLowWaterMark() {
    return writeBufferLowWaterMark;
  }

  public int writeBufferHighWaterMark() {
    return writeBufferHighWaterMark;
  }

  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public TrustLevel ingressTrustLevel() {
    return ingressTrustLevel;
  }

  public boolean trustForwardedHeaders() {
    return trustForwardedHeaders;
  }

  public long chunkedResponseThresholdBytes() {
    return chunkedResponseThresholdBytes;
  }

  private static void rejectUnknown(Map<String, String> values) {
    for (String key : values.keySet()) {
      Objects.requireNonNull(key, "configuration key");
      if (!KEYS.contains(key)) {
        throw new IllegalArgumentException("unknown gateway configuration property: " + key);
      }
    }
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing required gateway configuration property: " + key);
    }
    return value;
  }

  private static InetSocketAddress address(String host, int port, String label) {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    if (host.chars()
        .anyMatch(
            character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
      throw new IllegalArgumentException(label + " contains invalid characters");
    }
    return new InetSocketAddress(host, port);
  }

  private static InetSocketAddress requireListenAddress(InetSocketAddress value, String label) {
    return Objects.requireNonNull(value, label);
  }

  private static InetSocketAddress requireOriginAddress(InetSocketAddress value) {
    Objects.requireNonNull(value, "originAddress");
    if (value.getPort() == 0) {
      throw new IllegalArgumentException("originAddress must use a positive port");
    }
    return value;
  }

  private static Duration requirePositive(Duration value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    try {
      value.toNanos();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(label + " is too large", exception);
    }
    return value;
  }

  private static int requirePositive(int value, String label) {
    if (value <= 0) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return value;
  }

  private static int port(String value, String label, boolean allowZero) {
    int parsed = nonNegativeInt(value, label);
    int minimum = allowZero ? 0 : 1;
    if (parsed < minimum || parsed > 65_535) {
      throw new IllegalArgumentException(label + " must be between " + minimum + " and 65535");
    }
    return parsed;
  }

  private static int positiveInt(String value, String label) {
    int parsed = integer(value, label);
    if (parsed <= 0) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return parsed;
  }

  private static int nonNegativeInt(String value, String label) {
    int parsed = integer(value, label);
    if (parsed < 0) {
      throw new IllegalArgumentException(label + " must be non-negative");
    }
    return parsed;
  }

  private static int integer(String value, String label) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(label + " must be an integer", exception);
    }
  }

  private static long positiveLong(String value, String label) {
    long parsed = longValue(value, label);
    if (parsed <= 0) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return parsed;
  }

  private static long nonNegativeLong(String value, String label) {
    long parsed = longValue(value, label);
    if (parsed < 0) {
      throw new IllegalArgumentException(label + " must be non-negative");
    }
    return parsed;
  }

  private static long longValue(String value, String label) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(label + " must be a long integer", exception);
    }
  }

  private static Duration positiveDuration(String value, String label) {
    try {
      Duration duration = Duration.parse(value);
      return requirePositive(duration, label);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(label + " must be an ISO-8601 duration", exception);
    }
  }

  private static boolean booleanValue(String value, String label) {
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException(label + " must be true or false");
  }

  private static TrustLevel trust(String value) {
    try {
      return TrustLevel.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("ingress.trust must be TRUSTED or UNTRUSTED", exception);
    }
  }

  private static Duration minimum(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }

  public static final class Builder {
    private InetSocketAddress listenAddress;
    private InetSocketAddress adminAddress;
    private InetSocketAddress originAddress;
    private Path temporaryDirectory;
    private Budget globalBudget;
    private Duration failureCooldown;
    private int maxCooldownEntries;
    private int eventLoopThreads;
    private int originEventLoopThreads;
    private int maxClientConnections;
    private int serverBacklog;
    private int maxInitialLineLength;
    private int maxHeaderSize;
    private int maxChunkSize;
    private long maxRequestBodyBytes;
    private long maxSpoolBytes;
    private int maxSpoolFiles;
    private int originMaxConnections;
    private int originMaxPendingAcquires;
    private Duration originAcquireTimeout;
    private Duration requestTimeout;
    private Duration originConnectTimeout;
    private Duration originResponseTimeout;
    private Duration idleTimeout;
    private Duration drainTimeout;
    private int writeBufferLowWaterMark;
    private int writeBufferHighWaterMark;
    private int retryAfterSeconds;
    private TrustLevel ingressTrustLevel;
    private boolean trustForwardedHeaders;
    private long chunkedResponseThresholdBytes;

    private Builder() {}

    public Builder listenAddress(InetSocketAddress value) {
      listenAddress = value;
      return this;
    }

    public Builder adminAddress(InetSocketAddress value) {
      adminAddress = value;
      return this;
    }

    public Builder originAddress(InetSocketAddress value) {
      originAddress = value;
      return this;
    }

    public Builder temporaryDirectory(Path value) {
      temporaryDirectory = value;
      return this;
    }

    public Builder globalBudget(Budget value) {
      globalBudget = value;
      return this;
    }

    public Builder failureCooldown(Duration value) {
      failureCooldown = value;
      return this;
    }

    public Builder maxCooldownEntries(int value) {
      maxCooldownEntries = value;
      return this;
    }

    public Builder eventLoopThreads(int value) {
      eventLoopThreads = value;
      return this;
    }

    public Builder originEventLoopThreads(int value) {
      originEventLoopThreads = value;
      return this;
    }

    public Builder maxClientConnections(int value) {
      maxClientConnections = value;
      return this;
    }

    public Builder serverBacklog(int value) {
      serverBacklog = value;
      return this;
    }

    public Builder maxInitialLineLength(int value) {
      maxInitialLineLength = value;
      return this;
    }

    public Builder maxHeaderSize(int value) {
      maxHeaderSize = value;
      return this;
    }

    public Builder maxChunkSize(int value) {
      maxChunkSize = value;
      return this;
    }

    public Builder maxRequestBodyBytes(long value) {
      maxRequestBodyBytes = value;
      return this;
    }

    public Builder maxSpoolBytes(long value) {
      maxSpoolBytes = value;
      return this;
    }

    public Builder maxSpoolFiles(int value) {
      maxSpoolFiles = value;
      return this;
    }

    public Builder originMaxConnections(int value) {
      originMaxConnections = value;
      return this;
    }

    public Builder originMaxPendingAcquires(int value) {
      originMaxPendingAcquires = value;
      return this;
    }

    public Builder originAcquireTimeout(Duration value) {
      originAcquireTimeout = value;
      return this;
    }

    public Builder requestTimeout(Duration value) {
      requestTimeout = value;
      return this;
    }

    public Builder originConnectTimeout(Duration value) {
      originConnectTimeout = value;
      return this;
    }

    public Builder originResponseTimeout(Duration value) {
      originResponseTimeout = value;
      return this;
    }

    public Builder idleTimeout(Duration value) {
      idleTimeout = value;
      return this;
    }

    public Builder drainTimeout(Duration value) {
      drainTimeout = value;
      return this;
    }

    public Builder writeBufferLowWaterMark(int value) {
      writeBufferLowWaterMark = value;
      return this;
    }

    public Builder writeBufferHighWaterMark(int value) {
      writeBufferHighWaterMark = value;
      return this;
    }

    public Builder retryAfterSeconds(int value) {
      retryAfterSeconds = value;
      return this;
    }

    public Builder ingressTrustLevel(TrustLevel value) {
      ingressTrustLevel = value;
      return this;
    }

    public Builder trustForwardedHeaders(boolean value) {
      trustForwardedHeaders = value;
      return this;
    }

    public Builder chunkedResponseThresholdBytes(long value) {
      chunkedResponseThresholdBytes = value;
      return this;
    }

    public GatewayConfig build() {
      return new GatewayConfig(this);
    }
  }
}
