package io.github.aalsanie.boundedorigin.proxy;

import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class OriginWorkRegistry implements AutoCloseable {
  private static final int MAGIC = 0x424f5731;
  private static final int DIGEST_BYTES = 32;
  private static final int HEADER_BYTES = 40;
  private static final int RECORD_BYTES = 64;
  private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;

  private final GatewayConfig config;
  private final GatewayMetrics metrics;
  private final Map<String, Entry> outstanding = new HashMap<>();
  private final byte[] originIdentity;
  private FileChannel channel;
  private boolean started;
  private boolean closed;
  private boolean failed;

  OriginWorkRegistry(GatewayConfig config, GatewayMetrics metrics) {
    this.config = Objects.requireNonNull(config, "config");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    originIdentity =
        digest(
            config.originAddress().getHostString(),
            Integer.toString(config.originAddress().getPort()));
  }

  synchronized void start() throws IOException {
    if (started || closed) {
      throw new IllegalStateException("origin ownership registry cannot be started");
    }
    if (config.originCompletionContract() == OriginCompletionContract.DISABLED) {
      started = true;
      return;
    }
    Path directory = config.originOwnershipDirectory().orElseThrow();
    rejectSymlinks(directory);
    Files.createDirectories(directory);
    Path file = directory.resolve("ownership.bin");
    boolean created = false;
    try {
      try {
        channel =
            FileChannel.open(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        created = true;
      } catch (FileAlreadyExistsException exception) {
        channel =
            FileChannel.open(
                file, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      }
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException exception) {
        throw new IOException("origin ownership directory is already in use", exception);
      }
      if (lock == null) {
        throw new IOException("origin ownership directory is already in use");
      }
      if (created) {
        write(outstanding);
      } else {
        read();
      }
      started = true;
      updateMetrics();
    } catch (IOException | RuntimeException | Error exception) {
      close();
      throw exception;
    }
  }

  synchronized Permit reserve(OperationKey key, int policyCapacity) {
    Objects.requireNonNull(key, "key");
    if (policyCapacity <= 0) {
      throw new IllegalArgumentException("policyCapacity must be positive");
    }
    if (!started
        || closed
        || failed
        || config.originCompletionContract() == OriginCompletionContract.DISABLED) {
      throw new UnavailableException("origin computation admission is unavailable");
    }
    String identity =
        hex(
            digest(
                key.policyId(),
                Long.toString(key.policyVersion()),
                key.semanticIdentity(),
                key.materializerVersion()));
    String policy = hex(digest(key.policyId()));
    long policyOutstanding =
        outstanding.values().stream().filter(entry -> entry.policy.equals(policy)).count();
    if (outstanding.containsKey(identity)
        || outstanding.size() >= config.globalBudget().maxActive()
        || policyOutstanding >= policyCapacity) {
      throw new UnavailableException("origin computation capacity remains owned");
    }
    Entry entry = new Entry(identity, policy, false);
    outstanding.put(identity, entry);
    // Persistence precedes every possible upstream write. An uncertain write never authorizes work.
    persist(outstanding);
    updateMetrics();
    return new Permit(this, entry);
  }

  synchronized int unresolved() {
    return (int) outstanding.values().stream().filter(entry -> entry.unresolved).count();
  }

  synchronized int outstanding() {
    return outstanding.size();
  }

  private synchronized void complete(Entry expected) {
    if (closed || failed || outstanding.get(expected.identity) != expected) {
      return;
    }
    Map<String, Entry> remaining = new HashMap<>(outstanding);
    remaining.remove(expected.identity);
    persist(remaining);
    outstanding.remove(expected.identity);
    updateMetrics();
  }

  private synchronized void unresolved(Entry expected) {
    if (outstanding.get(expected.identity) == expected) {
      expected.unresolved = true;
      updateMetrics();
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    outstanding.values().forEach(entry -> entry.unresolved = true);
    updateMetrics();
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException exception) {
        StructuredLog.failure(0, "origin_ownership_close_failure", exception);
      }
    }
  }

  private void persist(Map<String, Entry> state) {
    try {
      write(state);
    } catch (IOException exception) {
      failed = true;
      outstanding.values().forEach(entry -> entry.unresolved = true);
      updateMetrics();
      throw new UnavailableException("origin ownership persistence failed", exception);
    }
  }

  private void write(Map<String, Entry> state) throws IOException {
    long size = HEADER_BYTES + (long) state.size() * RECORD_BYTES + DIGEST_BYTES;
    if (size > MAX_STATE_BYTES) {
      throw new IOException("origin ownership journal exceeds its size limit");
    }
    ByteBuffer data = ByteBuffer.allocate((int) size);
    data.putInt(MAGIC).put(originIdentity).putInt(state.size());
    for (Entry entry : new TreeMap<>(state).values()) {
      data.put(HexFormat.of().parseHex(entry.identity));
      data.put(HexFormat.of().parseHex(entry.policy));
    }
    data.put(sha256().digest(Arrays.copyOf(data.array(), data.position())));
    data.flip();
    channel.position(0);
    while (data.hasRemaining()) {
      channel.write(data);
    }
    channel.truncate(size);
    channel.force(true);
  }

  private void read() throws IOException {
    long size = channel.size();
    long maximum =
        HEADER_BYTES + (long) config.globalBudget().maxActive() * RECORD_BYTES + DIGEST_BYTES;
    if (size < HEADER_BYTES + DIGEST_BYTES || size > maximum || size > MAX_STATE_BYTES) {
      throw new IOException("invalid origin ownership journal size or reduced capacity");
    }
    ByteBuffer data = ByteBuffer.allocate((int) size);
    while (data.hasRemaining()) {
      if (channel.read(data) < 0) {
        throw new IOException("truncated origin ownership journal");
      }
    }
    byte[] payload = Arrays.copyOf(data.array(), (int) size - DIGEST_BYTES);
    byte[] checksum = Arrays.copyOfRange(data.array(), payload.length, (int) size);
    if (!MessageDigest.isEqual(sha256().digest(payload), checksum)) {
      throw new IOException("corrupt origin ownership journal");
    }
    data.flip();
    if (data.getInt() != MAGIC) {
      throw new IOException("unsupported origin ownership journal");
    }
    byte[] identity = new byte[DIGEST_BYTES];
    data.get(identity);
    if (!MessageDigest.isEqual(identity, originIdentity)) {
      throw new IOException("origin ownership journal belongs to a different origin");
    }
    int count = data.getInt();
    if (count < 0 || HEADER_BYTES + (long) count * RECORD_BYTES != payload.length) {
      throw new IOException("invalid origin ownership entry count");
    }
    for (int index = 0; index < count; index++) {
      byte[] key = new byte[DIGEST_BYTES];
      byte[] policy = new byte[DIGEST_BYTES];
      data.get(key).get(policy);
      String keyHash = hex(key);
      if (outstanding.put(keyHash, new Entry(keyHash, hex(policy), true)) != null) {
        throw new IOException("duplicate origin ownership entry");
      }
    }
  }

  private void updateMetrics() {
    metrics.originWorkState(
        outstanding.size(),
        unresolved(),
        started
            && !closed
            && !failed
            && config.originCompletionContract() != OriginCompletionContract.DISABLED);
  }

  private static void rejectSymlinks(Path directory) throws IOException {
    for (Path current = directory; current != null; current = current.getParent()) {
      if (Files.isSymbolicLink(current)) {
        throw new IOException("origin ownership directory must not contain symbolic links");
      }
    }
  }

  private static byte[] digest(String... values) {
    MessageDigest digest = sha256();
    for (String value : values) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }
    return digest.digest();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  static final class Permit implements AutoCloseable {
    private final OriginWorkRegistry owner;
    private final Entry entry;

    private Permit(OriginWorkRegistry owner, Entry entry) {
      this.owner = owner;
      this.entry = entry;
    }

    void completed() {
      owner.complete(entry);
    }

    @Override
    public void close() {
      owner.unresolved(entry);
    }
  }

  static final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnavailableException(String message) {
      super(message);
    }

    UnavailableException(String message, IOException cause) {
      super(message, cause);
    }
  }

  private static final class Entry {
    private final String identity;
    private final String policy;
    private boolean unresolved;

    private Entry(String identity, String policy, boolean unresolved) {
      this.identity = identity;
      this.policy = policy;
      this.unresolved = unresolved;
    }
  }
}
