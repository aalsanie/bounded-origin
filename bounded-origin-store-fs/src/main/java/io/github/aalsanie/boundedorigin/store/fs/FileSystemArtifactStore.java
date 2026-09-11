package io.github.aalsanie.boundedorigin.store.fs;

import io.github.aalsanie.boundedorigin.api.Artifact;
import io.github.aalsanie.boundedorigin.api.ArtifactBody;
import io.github.aalsanie.boundedorigin.api.ArtifactStore;
import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

public final class FileSystemArtifactStore implements ArtifactStore, AutoCloseable {
  private static final int BUFFER_SIZE = 32 * 1024;
  private static final long PROCESS_LOCK_RETRY_NANOS = 1_000_000_000L;
  private static final long PROCESS_LOCK_RETRY_SLEEP_MILLIS = 10L;
  private static final boolean WINDOWS = java.io.File.separatorChar == '\\';
  private static final Set<String> UNSAFE_METADATA =
      Set.of(
          "authorization",
          "connection",
          "content-length",
          "cookie",
          "date",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "proxy-connection",
          "set-cookie",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "www-authenticate");

  private final Path root;
  private final Path objectsDirectory;
  private final Path entriesDirectory;
  private final Path tempDirectory;
  private final long maxStoreBytes;
  private final long maxArtifactBytes;
  private final TempOutputFactory outputFactory;
  private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
  private final Map<String, StoreEntry> entries = new HashMap<>();
  private final Map<String, Integer> objectReferences = new HashMap<>();
  private final Map<String, Long> objectSizes = new HashMap<>();
  private final Set<String> pendingObjectDeletes = new HashSet<>();
  private final ConcurrentHashMap<String, AtomicInteger> openReaders = new ConcurrentHashMap<>();
  private final AtomicInteger activeWriters = new AtomicInteger();
  private final AtomicInteger totalOpenReaders = new AtomicInteger();
  private final AtomicLong corruptionCount = new AtomicLong();
  private final AtomicLong evictionCount = new AtomicLong();
  private final Object resourceLock = new Object();

  private FileChannel lockChannel;
  private FileLock processLock;
  private volatile boolean closed;
  private boolean resourcesReleased;
  private long storedBytes;
  private long nextGeneration;

  public FileSystemArtifactStore(Path root, long maxStoreBytes, long maxArtifactBytes)
      throws IOException {
    this(
        root,
        maxStoreBytes,
        maxArtifactBytes,
        path ->
            Files.newOutputStream(
                path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
  }

  FileSystemArtifactStore(
      Path root, long maxStoreBytes, long maxArtifactBytes, TempOutputFactory outputFactory)
      throws IOException {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    if (maxStoreBytes <= 0) {
      throw new IllegalArgumentException("maxStoreBytes must be positive");
    }
    if (maxArtifactBytes < 0 || maxArtifactBytes > maxStoreBytes) {
      throw new IllegalArgumentException(
          "maxArtifactBytes must be non-negative and no greater than maxStoreBytes");
    }
    this.maxStoreBytes = maxStoreBytes;
    this.maxArtifactBytes = maxArtifactBytes;
    this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
    objectsDirectory = this.root.resolve("objects");
    entriesDirectory = this.root.resolve("entries");
    tempDirectory = this.root.resolve("tmp");

    boolean initialized = false;
    try {
      initializeDirectories();
      acquireProcessLock();
      verifyAtomicMoveSupport();
      recover();
      initialized = true;
    } finally {
      if (!initialized) {
        releaseResources();
      }
    }
  }

  @Override
  public Optional<Artifact> get(OperationKey key) throws IOException {
    Objects.requireNonNull(key, "key");
    String keyHash = keyHash(key);
    StoreEntry entry = null;
    CorruptStoreException corruption = null;

    stateLock.readLock().lock();
    try {
      ensureOpen();
      entry = entries.get(keyHash);
      if (entry == null) {
        return Optional.empty();
      }
      if (!entry.key().equals(key)) {
        corruption = new CorruptStoreException("operation-key hash collision", false);
      } else {
        StoreEntry diskEntry = StoreEntryCodec.read(entryPath(keyHash), keyHash);
        validateEntry(diskEntry);
        if (!entry.equals(diskEntry)) {
          corruption = new CorruptStoreException("entry changed after recovery", false);
        } else {
          verifyObject(entry);
          StoreEntry artifactEntry = entry;
          ArtifactBody body = () -> openBody(artifactEntry);
          return Optional.of(
              new Artifact(entry.statusCode(), entry.contentLength(), entry.metadata(), body));
        }
      }
    } catch (CorruptStoreException exception) {
      corruption = exception;
    } finally {
      stateLock.readLock().unlock();
    }

    if (entry == null) {
      throw new IOException("artifact store state became inconsistent");
    }
    removeCorruption(entry, corruption.objectCorruption());
    IOException failure = new IOException("artifact corruption detected for operation key");
    failure.initCause(corruption);
    throw failure;
  }

  @Override
  public void put(OperationKey key, Artifact artifact) throws IOException {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(artifact, "artifact");
    validateArtifact(artifact);
    beginWriter();

    Path bodyTemp = null;
    try {
      StagedBody stagedBody = stageBody(artifact);
      bodyTemp = stagedBody.path();
      commit(key, artifact, stagedBody);
    } finally {
      deleteTemporary(bodyTemp);
      endWriter();
    }
  }

  public FileSystemArtifactStoreStats stats() {
    stateLock.readLock().lock();
    try {
      return new FileSystemArtifactStoreStats(
          storedBytes, entries.size(), corruptionCount.get(), evictionCount.get());
    } finally {
      stateLock.readLock().unlock();
    }
  }

  @Override
  public void close() throws IOException {
    stateLock.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
    } finally {
      stateLock.writeLock().unlock();
    }
    maybeReleaseResources();
  }

  private void initializeDirectories() throws IOException {
    Files.createDirectories(root);
    Files.createDirectories(objectsDirectory);
    Files.createDirectories(entriesDirectory);
    Files.createDirectories(tempDirectory);
  }

  private void acquireProcessLock() throws IOException {
    Path lockFile = root.resolve(".lock");
    lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    long deadline = System.nanoTime() + PROCESS_LOCK_RETRY_NANOS;
    while (true) {
      try {
        processLock = lockChannel.tryLock();
      } catch (OverlappingFileLockException exception) {
        throw new IOException("artifact store is already open in this process", exception);
      }
      if (processLock != null) {
        return;
      }
      if (System.nanoTime() - deadline >= 0) {
        throw new IOException("artifact store is already locked by another process");
      }
      try {
        Thread.sleep(PROCESS_LOCK_RETRY_SLEEP_MILLIS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while waiting for artifact store lock", exception);
      }
    }
  }

  private void verifyAtomicMoveSupport() throws IOException {
    Path source = Files.createTempFile(tempDirectory, "atomic-", ".tmp");
    Path target = source.resolveSibling(source.getFileName() + ".moved");
    try {
      forceFile(source);
      atomicMove(source, target);
      forceDirectory(tempDirectory);
    } finally {
      Files.deleteIfExists(source);
      Files.deleteIfExists(target);
    }
  }

  private void recover() throws IOException {
    cleanupTempDirectory();
    long maxGeneration = -1;

    try (Stream<Path> paths = Files.walk(entriesDirectory)) {
      List<Path> entryFiles =
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .sorted()
              .toList();
      for (Path path : entryFiles) {
        String keyHash = entryHash(path);
        if (keyHash == null || !path.normalize().equals(entryPath(keyHash))) {
          deleteRecoveredCorruption(path);
          continue;
        }

        StoreEntry entry;
        try {
          entry = StoreEntryCodec.read(path, keyHash);
          validateEntry(entry);
          if (!keyHash(entry.key()).equals(keyHash)) {
            throw new CorruptStoreException("entry key hash mismatch", false);
          }
          Path objectPath = objectPath(entry.contentDigest());
          if (!Files.isRegularFile(objectPath, LinkOption.NOFOLLOW_LINKS)
              || Files.size(objectPath) != entry.contentLength()) {
            throw new CorruptStoreException("missing or truncated object", true);
          }
        } catch (CorruptStoreException exception) {
          deleteRecoveredCorruption(path);
          continue;
        }

        entries.put(keyHash, entry);
        objectReferences.merge(entry.contentDigest(), 1, Integer::sum);
        storedBytes = checkedAdd(storedBytes, entry.entryFileSize());
        maxGeneration = Math.max(maxGeneration, entry.generation());
      }
    }

    try (Stream<Path> paths = Files.walk(objectsDirectory)) {
      List<Path> objectFiles =
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .sorted()
              .toList();
      for (Path path : objectFiles) {
        String digest = objectDigest(path);
        if (digest == null || !path.normalize().equals(objectPath(digest))) {
          Files.deleteIfExists(path);
          continue;
        }
        if (!objectReferences.containsKey(digest)) {
          Files.deleteIfExists(path);
          continue;
        }
        long size = Files.size(path);
        objectSizes.put(digest, size);
        storedBytes = checkedAdd(storedBytes, size);
      }
    }

    if (storedBytes > maxStoreBytes) {
      evictToFit(0, null);
    }
    if (maxGeneration == Long.MAX_VALUE) {
      throw new IOException("artifact generation exhausted");
    }
    nextGeneration = maxGeneration + 1;
  }

  private void cleanupTempDirectory() throws IOException {
    try (Stream<Path> paths = Files.list(tempDirectory)) {
      for (Path path : paths.toList()) {
        if (Files.isDirectory(path)) {
          deleteTree(path);
        } else {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private void deleteTree(Path rootPath) throws IOException {
    try (Stream<Path> paths = Files.walk(rootPath)) {
      List<Path> values = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path path : values) {
        Files.deleteIfExists(path);
      }
    }
  }

  private void deleteRecoveredCorruption(Path path) throws IOException {
    Files.deleteIfExists(path);
    corruptionCount.incrementAndGet();
  }

  private void validateArtifact(Artifact artifact) throws IOException {
    if (artifact.contentLength() > maxArtifactBytes) {
      throw new IOException("artifact exceeds configured maximum size");
    }
    validateMetadata(artifact.metadata());
  }

  private void validateEntry(StoreEntry entry) throws CorruptStoreException {
    if (entry.generation() < 0
        || entry.statusCode() < 200
        || entry.statusCode() > 599
        || entry.contentLength() < 0
        || entry.contentLength() > maxArtifactBytes
        || !isDigest(entry.contentDigest())) {
      throw new CorruptStoreException("invalid entry values", false);
    }
    try {
      validateMetadata(entry.metadata());
    } catch (IOException exception) {
      throw new CorruptStoreException("unsafe persisted metadata", false);
    }
  }

  private void validateMetadata(Map<String, String> metadata) throws IOException {
    if (metadata.size() > 256) {
      throw new IOException("artifact metadata contains too many entries");
    }
    Set<String> normalizedNames = new HashSet<>();
    for (Map.Entry<String, String> value : metadata.entrySet()) {
      String name = value.getKey();
      String content = value.getValue();
      if (!isMetadataName(name) || containsUnsafeMetadataValueCharacter(content)) {
        throw new IOException("artifact metadata contains unsafe characters");
      }
      String normalizedName = name.toLowerCase(Locale.ROOT);
      if (!normalizedNames.add(normalizedName)) {
        throw new IOException("artifact metadata contains duplicate response names");
      }
      if (UNSAFE_METADATA.contains(normalizedName)) {
        throw new IOException("artifact metadata contains unsafe response metadata: " + name);
      }
    }
  }

  private static boolean isMetadataName(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean alphaNumeric =
          (character >= 'a' && character <= 'z')
              || (character >= 'A' && character <= 'Z')
              || (character >= '0' && character <= '9');
      boolean punctuation =
          character == '!'
              || character == '#'
              || character == '$'
              || character == '%'
              || character == '&'
              || character == '\''
              || character == '*'
              || character == '+'
              || character == '-'
              || character == '.'
              || character == '^'
              || character == '_'
              || character == '`'
              || character == '|'
              || character == '~';
      if (!alphaNumeric && !punctuation) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsUnsafeMetadataValueCharacter(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if ((character < 0x20 && character != '\t') || character == 0x7f) {
        return true;
      }
    }
    return false;
  }

  private StagedBody stageBody(Artifact artifact) throws IOException {
    Path path = Files.createTempFile(tempDirectory, "body-", ".tmp");
    MessageDigest digest = sha256();
    long written = 0;

    try {
      InputStream opened = artifact.body().openStream();
      if (opened == null) {
        throw new IOException("artifact body returned a null stream");
      }
      try (InputStream input = new BufferedInputStream(opened, BUFFER_SIZE);
          OutputStream raw = outputFactory.open(path);
          OutputStream output = new BufferedOutputStream(raw, BUFFER_SIZE)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
          int count = input.read(buffer);
          if (count < 0) {
            break;
          }
          if (count == 0) {
            continue;
          }
          if (written > artifact.contentLength() - count) {
            throw new IOException("artifact body exceeds declared content length");
          }
          digest.update(buffer, 0, count);
          output.write(buffer, 0, count);
          written += count;
        }
      }
      if (written != artifact.contentLength()) {
        throw new IOException("artifact body does not match declared content length");
      }
      forceFile(path);
      return new StagedBody(path, toHex(digest.digest()), written);
    } catch (IOException | RuntimeException | Error exception) {
      Files.deleteIfExists(path);
      throw exception;
    }
  }

  private void commit(OperationKey key, Artifact artifact, StagedBody stagedBody)
      throws IOException {
    String keyHash = keyHash(key);
    Path entryTemp = null;

    stateLock.writeLock().lock();
    try {
      ensureOpen();
      StoreEntry existing = entries.get(keyHash);
      if (existing != null) {
        verifyExistingEntry(key, existing);
        if (!existing.matches(artifact, stagedBody.digest())) {
          throw new IOException("operation key already references a different immutable artifact");
        }
        return;
      }

      ensureExistingObjectUsable(stagedBody.digest(), stagedBody.length());
      boolean objectExists =
          Files.isRegularFile(objectPath(stagedBody.digest()), LinkOption.NOFOLLOW_LINKS);
      if (nextGeneration == Long.MAX_VALUE) {
        throw new IOException("artifact generation exhausted");
      }
      StoreEntry entry =
          new StoreEntry(
              keyHash,
              key,
              artifact.statusCode(),
              artifact.contentLength(),
              stagedBody.digest(),
              nextGeneration,
              artifact.metadata(),
              0);
      entryTemp = Files.createTempFile(tempDirectory, "entry-", ".tmp");
      long entrySize = StoreEntryCodec.write(entryTemp, entry, outputFactory);
      forceFile(entryTemp);
      entry = entry.withEntryFileSize(entrySize);

      long additional = checkedAdd(objectExists ? 0 : stagedBody.length(), entrySize);
      evictToFit(additional, stagedBody.digest());

      boolean objectPublished = false;
      try {
        if (!objectExists) {
          publishObject(stagedBody.path(), stagedBody.digest(), stagedBody.length());
          objectPublished = true;
        }
        publishEntry(entryTemp, entry);
        entryTemp = null;
        entries.put(keyHash, entry);
        objectReferences.merge(entry.contentDigest(), 1, Integer::sum);
        storedBytes = checkedAdd(storedBytes, entry.entryFileSize());
        nextGeneration++;
      } catch (IOException | RuntimeException | Error exception) {
        if (objectPublished && !objectReferences.containsKey(stagedBody.digest())) {
          deleteUnreferencedObject(stagedBody.digest());
        }
        throw exception;
      }
    } finally {
      stateLock.writeLock().unlock();
      deleteTemporary(entryTemp);
    }
  }

  private void verifyExistingEntry(OperationKey key, StoreEntry entry) throws IOException {
    if (!entry.key().equals(key)) {
      throw new IOException("operation-key hash collision");
    }
    StoreEntry diskEntry = StoreEntryCodec.read(entryPath(entry.keyHash()), entry.keyHash());
    validateEntry(diskEntry);
    if (!entry.equals(diskEntry)) {
      throw new IOException("existing entry changed after recovery");
    }
    verifyObject(entry);
  }

  private void ensureExistingObjectUsable(String digest, long length) throws IOException {
    Path path = objectPath(digest);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    boolean corrupt =
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != length;
    if (!corrupt) {
      corrupt = !digest.equals(digestFile(path));
    }
    if (!corrupt) {
      return;
    }

    removeEntriesForObject(digest, true);
    corruptionCount.incrementAndGet();
    if (readerCount(digest) > 0) {
      pendingObjectDeletes.add(digest);
      throw new IOException("corrupt object is still pinned by an open reader");
    }
    deleteUnreferencedObject(digest);
  }

  private void publishObject(Path source, String digest, long length) throws IOException {
    Path target = objectPath(digest);
    Path parent = Objects.requireNonNull(target.getParent(), "object target parent");
    Files.createDirectories(parent);
    long updatedStoredBytes = checkedAdd(storedBytes, length);
    atomicMove(source, target);
    try {
      forceDirectory(parent);
    } catch (IOException exception) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }
    objectSizes.put(digest, length);
    storedBytes = updatedStoredBytes;
  }

  private void publishEntry(Path source, StoreEntry entry) throws IOException {
    Path target = entryPath(entry.keyHash());
    Path parent = Objects.requireNonNull(target.getParent(), "entry target parent");
    Files.createDirectories(parent);
    atomicMove(source, target);
    forceDirectory(parent);
  }

  private void evictToFit(long additionalBytes, String preserveObjectDigest) throws IOException {
    if (additionalBytes > maxStoreBytes) {
      throw new IOException("artifact cannot fit within configured store capacity");
    }
    if (!exceedsCapacity(additionalBytes)) {
      return;
    }

    List<StoreEntry> candidates =
        entries.values().stream()
            .sorted(
                Comparator.comparingLong(StoreEntry::generation).thenComparing(StoreEntry::keyHash))
            .toList();
    for (StoreEntry candidate : candidates) {
      if (!exceedsCapacity(additionalBytes)) {
        break;
      }
      if (readerCount(candidate.contentDigest()) > 0) {
        continue;
      }
      removeEntry(candidate, preserveObjectDigest);
      evictionCount.incrementAndGet();
    }
    if (exceedsCapacity(additionalBytes)) {
      throw new IOException("artifact store capacity exhausted");
    }
  }

  private boolean exceedsCapacity(long additionalBytes) {
    return storedBytes > maxStoreBytes - additionalBytes;
  }

  private void removeCorruption(StoreEntry detected, boolean objectCorruption) throws IOException {
    stateLock.writeLock().lock();
    try {
      StoreEntry current = entries.get(detected.keyHash());
      if (current == null || current.generation() != detected.generation()) {
        return;
      }
      if (objectCorruption) {
        removeEntriesForObject(detected.contentDigest(), false);
        if (readerCount(detected.contentDigest()) > 0) {
          pendingObjectDeletes.add(detected.contentDigest());
        } else {
          deleteUnreferencedObject(detected.contentDigest());
        }
      } else {
        removeEntry(detected, null);
      }
      corruptionCount.incrementAndGet();
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private void removeEntriesForObject(String digest, boolean preserveObject) throws IOException {
    List<StoreEntry> affected =
        entries.values().stream().filter(entry -> entry.contentDigest().equals(digest)).toList();
    for (StoreEntry entry : affected) {
      removeEntry(entry, preserveObject ? digest : null);
    }
  }

  private void removeEntry(StoreEntry entry, String preserveObjectDigest) throws IOException {
    StoreEntry current = entries.get(entry.keyHash());
    if (current == null || current.generation() != entry.generation()) {
      return;
    }

    Files.deleteIfExists(entryPath(entry.keyHash()));
    storedBytes = checkedSubtract(storedBytes, entry.entryFileSize());
    entries.remove(entry.keyHash());

    int references = objectReferences.getOrDefault(entry.contentDigest(), 0);
    if (references <= 0) {
      throw new IOException("artifact store reference accounting is inconsistent");
    }
    if (references == 1) {
      objectReferences.remove(entry.contentDigest());
      if (!entry.contentDigest().equals(preserveObjectDigest)) {
        if (readerCount(entry.contentDigest()) > 0) {
          pendingObjectDeletes.add(entry.contentDigest());
        } else {
          deleteUnreferencedObject(entry.contentDigest());
        }
      }
    } else {
      objectReferences.put(entry.contentDigest(), references - 1);
    }
  }

  private void deleteUnreferencedObject(String digest) throws IOException {
    if (objectReferences.containsKey(digest)) {
      return;
    }
    Long accountedSize = objectSizes.remove(digest);
    Path path = objectPath(digest);
    if (Files.deleteIfExists(path)) {
      forceDirectory(path.getParent());
    }
    if (accountedSize != null) {
      storedBytes = checkedSubtract(storedBytes, accountedSize);
    }
    pendingObjectDeletes.remove(digest);
  }

  private void verifyObject(StoreEntry entry) throws IOException {
    Path path = objectPath(entry.contentDigest());
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new CorruptStoreException("artifact object is missing", true);
    }
    if (Files.size(path) != entry.contentLength()) {
      throw new CorruptStoreException("artifact object has an invalid length", true);
    }
    if (!entry.contentDigest().equals(digestFile(path))) {
      throw new CorruptStoreException("artifact object digest mismatch", true);
    }
  }

  private String digestFile(Path path) throws IOException {
    MessageDigest digest = sha256();
    try (InputStream input = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE)) {
      byte[] buffer = new byte[BUFFER_SIZE];
      while (true) {
        int count = input.read(buffer);
        if (count < 0) {
          break;
        }
        if (count > 0) {
          digest.update(buffer, 0, count);
        }
      }
    }
    return toHex(digest.digest());
  }

  private InputStream openBody(StoreEntry entry) throws IOException {
    boolean readerReserved = false;
    try {
      stateLock.readLock().lock();
      try {
        ensureOpen();
        StoreEntry current = entries.get(entry.keyHash());
        if (current == null || current.generation() != entry.generation()) {
          throw new IOException("artifact was evicted before its body was opened");
        }
        AtomicInteger readers =
            openReaders.computeIfAbsent(entry.contentDigest(), ignored -> new AtomicInteger());
        readers.incrementAndGet();
        totalOpenReaders.incrementAndGet();
        readerReserved = true;

        Path path = objectPath(entry.contentDigest());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("artifact object is no longer a regular file");
        }
        InputStream input = Files.newInputStream(path);
        return new ReaderInputStream(input, entry.contentDigest());
      } finally {
        stateLock.readLock().unlock();
      }
    } catch (IOException | RuntimeException | Error exception) {
      if (readerReserved) {
        try {
          releaseReader(entry.contentDigest());
        } catch (IOException releaseFailure) {
          exception.addSuppressed(releaseFailure);
        }
      }
      throw exception;
    }
  }

  private int readerCount(String digest) {
    AtomicInteger count = openReaders.get(digest);
    return count == null ? 0 : count.get();
  }

  private void releaseReader(String digest) throws IOException {
    AtomicInteger readers = openReaders.get(digest);
    if (readers == null) {
      throw new IOException("artifact reader accounting is inconsistent");
    }
    int remaining = readers.decrementAndGet();
    if (remaining < 0) {
      throw new IOException("artifact reader accounting became negative");
    }

    IOException failure = null;
    if (remaining == 0) {
      openReaders.remove(digest, readers);
      stateLock.writeLock().lock();
      try {
        if (pendingObjectDeletes.contains(digest) && !objectReferences.containsKey(digest)) {
          deleteUnreferencedObject(digest);
        }
      } catch (IOException exception) {
        failure = exception;
      } finally {
        stateLock.writeLock().unlock();
      }
    }

    int total = totalOpenReaders.decrementAndGet();
    if (total < 0) {
      IOException accounting = new IOException("artifact reader total became negative");
      if (failure == null) {
        failure = accounting;
      } else {
        failure.addSuppressed(accounting);
      }
    }
    try {
      maybeReleaseResources();
    } catch (IOException exception) {
      if (failure == null) {
        failure = exception;
      } else {
        failure.addSuppressed(exception);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void beginWriter() throws IOException {
    stateLock.readLock().lock();
    try {
      ensureOpen();
      activeWriters.incrementAndGet();
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void endWriter() throws IOException {
    int remaining = activeWriters.decrementAndGet();
    if (remaining < 0) {
      throw new IOException("artifact writer accounting became negative");
    }
    maybeReleaseResources();
  }

  private void maybeReleaseResources() throws IOException {
    if (!closed || activeWriters.get() != 0 || totalOpenReaders.get() != 0) {
      return;
    }
    releaseResources();
  }

  private void releaseResources() throws IOException {
    synchronized (resourceLock) {
      if (resourcesReleased) {
        return;
      }
      IOException failure = null;
      if (processLock != null) {
        try {
          processLock.release();
        } catch (IOException exception) {
          failure = exception;
        }
      }
      if (lockChannel != null) {
        try {
          lockChannel.close();
        } catch (IOException exception) {
          if (failure == null) {
            failure = exception;
          } else {
            failure.addSuppressed(exception);
          }
        }
      }
      resourcesReleased = true;
      if (failure != null) {
        throw failure;
      }
    }
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("artifact store is closed");
    }
  }

  private Path objectPath(String digest) {
    return objectsDirectory
        .resolve(digest.substring(0, 2))
        .resolve(digest.substring(2, 4))
        .resolve(digest);
  }

  private Path entryPath(String keyHash) {
    return entriesDirectory
        .resolve(keyHash.substring(0, 2))
        .resolve(keyHash.substring(2, 4))
        .resolve(keyHash + ".entry");
  }

  private String entryHash(Path path) {
    String name = Objects.requireNonNull(path.getFileName(), "entry file name").toString();
    if (!name.endsWith(".entry")) {
      return null;
    }
    String value = name.substring(0, name.length() - ".entry".length());
    return isDigest(value) ? value : null;
  }

  private String objectDigest(Path path) {
    String value = Objects.requireNonNull(path.getFileName(), "object file name").toString();
    return isDigest(value) ? value : null;
  }

  private static boolean isDigest(String value) {
    if (value.length() != 64) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      boolean digit = character >= '0' && character <= '9';
      boolean lower = character >= 'a' && character <= 'f';
      if (!digit && !lower) {
        return false;
      }
    }
    return true;
  }

  private String keyHash(OperationKey key) {
    MessageDigest digest = sha256();
    updateString(digest, key.policyId());
    updateLong(digest, key.policyVersion());
    updateString(digest, key.semanticIdentity());
    updateString(digest, key.materializerVersion());
    return toHex(digest.digest());
  }

  private static void updateString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    updateInt(digest, bytes.length);
    digest.update(bytes);
  }

  private static void updateInt(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static void updateLong(MessageDigest digest, long value) {
    digest.update((byte) (value >>> 56));
    digest.update((byte) (value >>> 48));
    digest.update((byte) (value >>> 40));
    digest.update((byte) (value >>> 32));
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String toHex(byte[] bytes) {
    char[] characters = new char[bytes.length * 2];
    char[] alphabet = "0123456789abcdef".toCharArray();
    for (int index = 0; index < bytes.length; index++) {
      int value = bytes[index] & 0xff;
      characters[index * 2] = alphabet[value >>> 4];
      characters[index * 2 + 1] = alphabet[value & 0x0f];
    }
    return new String(characters);
  }

  private static long checkedAdd(long left, long right) throws IOException {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      throw new IOException("artifact store size accounting overflow", exception);
    }
  }

  private static long checkedSubtract(long left, long right) throws IOException {
    if (left < right) {
      throw new IOException("artifact store size accounting underflow");
    }
    return left - right;
  }

  private static void atomicMove(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      throw new IOException("artifact store requires atomic moves on its filesystem", exception);
    }
  }

  private static void forceFile(Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static void forceDirectory(Path path) throws IOException {
    if (WINDOWS) {
      return;
    }
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void deleteTemporary(Path path) throws IOException {
    if (path != null) {
      Files.deleteIfExists(path);
    }
  }

  private record StagedBody(Path path, String digest, long length) {}

  private final class ReaderInputStream extends FilterInputStream {
    private final String digest;
    private final AtomicBoolean released = new AtomicBoolean();

    private ReaderInputStream(InputStream input, String digest) {
      super(input);
      this.digest = digest;
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      try {
        super.close();
      } catch (IOException exception) {
        failure = exception;
      }
      if (released.compareAndSet(false, true)) {
        try {
          releaseReader(digest);
        } catch (IOException exception) {
          if (failure == null) {
            failure = exception;
          } else {
            failure.addSuppressed(exception);
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }
}
