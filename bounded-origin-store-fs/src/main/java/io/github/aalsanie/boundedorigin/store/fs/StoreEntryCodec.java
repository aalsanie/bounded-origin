package io.github.aalsanie.boundedorigin.store.fs;

import io.github.aalsanie.boundedorigin.api.OperationKey;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class StoreEntryCodec {
  static final long MAX_ENTRY_BYTES = 262_144;

  private static final int MAGIC = 0x424f4531;
  private static final int VERSION = 1;
  private static final int MAX_FIELD_BYTES = 65_536;
  private static final int MAX_METADATA_ENTRIES = 256;

  private StoreEntryCodec() {}

  static long write(Path path, StoreEntry entry, TempOutputFactory outputFactory)
      throws IOException {
    try (OutputStream raw = outputFactory.open(path);
        LimitedOutputStream limited = new LimitedOutputStream(raw, MAX_ENTRY_BYTES);
        DataOutputStream output = new DataOutputStream(limited)) {
      output.writeInt(MAGIC);
      output.writeInt(VERSION);
      output.writeLong(entry.generation());
      output.writeInt(entry.statusCode());
      output.writeLong(entry.contentLength());
      writeString(output, entry.contentDigest());
      writeString(output, entry.key().policyId());
      output.writeLong(entry.key().policyVersion());
      writeString(output, entry.key().semanticIdentity());
      writeString(output, entry.key().materializerVersion());

      Map<String, String> metadata = new TreeMap<>(entry.metadata());
      if (metadata.size() > MAX_METADATA_ENTRIES) {
        throw new IOException("artifact metadata contains too many entries");
      }
      output.writeInt(metadata.size());
      for (Map.Entry<String, String> value : metadata.entrySet()) {
        writeString(output, value.getKey());
        writeString(output, value.getValue());
      }
    }
    return Files.size(path);
  }

  static StoreEntry read(Path path, String keyHash) throws IOException {
    long size = Files.size(path);
    if (size <= 0 || size > MAX_ENTRY_BYTES) {
      throw new CorruptStoreException("invalid entry size", false);
    }

    try (InputStream raw = Files.newInputStream(path);
        DataInputStream input = new DataInputStream(new BufferedInputStream(raw, 8_192))) {
      if (input.readInt() != MAGIC || input.readInt() != VERSION) {
        throw new CorruptStoreException("invalid entry header", false);
      }

      long generation = input.readLong();
      int statusCode = input.readInt();
      long contentLength = input.readLong();
      String contentDigest = readString(input);
      String policyId = readString(input);
      long policyVersion = input.readLong();
      String semanticIdentity = readString(input);
      String materializerVersion = readString(input);
      int metadataCount = input.readInt();
      if (metadataCount < 0 || metadataCount > MAX_METADATA_ENTRIES) {
        throw new CorruptStoreException("invalid metadata count", false);
      }

      Map<String, String> metadata = new LinkedHashMap<>();
      for (int index = 0; index < metadataCount; index++) {
        String name = readString(input);
        String value = readString(input);
        if (metadata.put(name, value) != null) {
          throw new CorruptStoreException("duplicate metadata key", false);
        }
      }
      if (input.read() != -1) {
        throw new CorruptStoreException("trailing entry bytes", false);
      }

      OperationKey key;
      try {
        key = new OperationKey(policyId, policyVersion, semanticIdentity, materializerVersion);
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw new CorruptStoreException("invalid operation key", false);
      }

      return new StoreEntry(
          keyHash, key, statusCode, contentLength, contentDigest, generation, metadata, size);
    } catch (CorruptStoreException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new CorruptStoreException("invalid entry encoding", false);
    }
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_FIELD_BYTES) {
      throw new IOException("entry field exceeds encoded size limit");
    }
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_FIELD_BYTES) {
      throw new CorruptStoreException("invalid string length", false);
    }
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      throw new CorruptStoreException("truncated string", false);
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static final class LimitedOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final long limit;
    private long count;

    private LimitedOutputStream(OutputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
      reserve(1);
      delegate.write(value);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      reserve(length);
      delegate.write(buffer, offset, length);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    private void reserve(int length) throws IOException {
      if (length < 0 || count > limit - length) {
        throw new IOException("entry metadata exceeds encoded size limit");
      }
      count += length;
    }
  }
}
