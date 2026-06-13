package com.tpverp.backend.backup.application;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class BackupFileCrypto {

    private static final byte[] MAGIC = {'T', 'P', 'V', 'B'};
    private static final int VERSION = 1;
    private static final int MIN_CHUNK_SIZE = 1024;
    private static final int MAX_CHUNK_SIZE = 16 * 1024 * 1024;
    private static final int KEY_LENGTH = 32;
    private static final int WRAP_NONCE_LENGTH = 12;
    private static final int NONCE_PREFIX_LENGTH = 8;
    private static final int GCM_TAG_LENGTH = 16;
    private static final int GCM_TAG_BITS = GCM_TAG_LENGTH * 8;
    private static final int WRAPPED_DEK_LENGTH = KEY_LENGTH + GCM_TAG_LENGTH;
    private static final int HASH_LENGTH = 32;
    private static final int ENCRYPTED_HASH_LENGTH = HASH_LENGTH + GCM_TAG_LENGTH;

    private final int chunkSize;
    private final SecureRandom random;

    public BackupFileCrypto(int chunkSize) {
        this(chunkSize, new SecureRandom());
    }

    BackupFileCrypto(int chunkSize, SecureRandom random) {
        if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                "Chunk size must be between 1024 and 16777216 bytes");
        }
        this.chunkSize = chunkSize;
        this.random = random;
    }

    public BackupInfo encrypt(Path source, Path destination, byte[] brk)
        throws IOException, GeneralSecurityException {
        validateBrk(brk);
        long plaintextLength = Files.size(source);
        int chunkCount = chunkCount(plaintextLength, chunkSize);
        BackupInfo info = new BackupInfo(VERSION, chunkSize, plaintextLength, chunkCount);
        Path temporary = createTemporarySibling(destination);
        try {
            encryptTo(source, temporary, brk, info);
            promoteAtomically(temporary, destination);
            return info;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public BackupInfo decrypt(Path backup, Path destination, byte[] brk)
        throws IOException, GeneralSecurityException {
        validateBrk(brk);
        Path temporary = createTemporarySibling(destination);
        try {
            BackupInfo info = decryptTo(backup, temporary, brk);
            promoteAtomically(temporary, destination);
            return info;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public BackupInfo inspect(Path backup) throws IOException, GeneralSecurityException {
        try (var input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(backup)))) {
            return readHeader(input).info();
        }
    }

    private void encryptTo(Path source, Path temporary, byte[] brk, BackupInfo info)
        throws IOException, GeneralSecurityException {
        byte[] dek = randomBytes(KEY_LENGTH);
        byte[] wrapNonce = randomBytes(WRAP_NONCE_LENGTH);
        byte[] noncePrefix = randomBytes(NONCE_PREFIX_LENGTH);
        try (var fileOutput = new FileOutputStream(temporary.toFile());
             var output = new DataOutputStream(new BufferedOutputStream(fileOutput));
             var input = new BufferedInputStream(Files.newInputStream(source))) {
            byte[] baseHeader = baseHeader(info, wrapNonce, noncePrefix);
            byte[] wrappedDek = crypt(
                Cipher.ENCRYPT_MODE,
                brk,
                wrapNonce,
                baseHeader,
                dek);
            byte[] header = completeHeader(baseHeader, wrappedDek);
            output.write(header);
            byte[] headerDigest = sha256(header);
            MessageDigest plaintextDigest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[info.chunkSize()];
            long remaining = info.plaintextLength();

            for (int index = 0; index < info.chunkCount(); index++) {
                int expected = (int) Math.min(buffer.length, remaining);
                int read = readFully(input, buffer, expected);
                if (read != expected) {
                    throw new IOException("Source file changed while it was being encrypted");
                }
                plaintextDigest.update(buffer, 0, read);
                byte[] ciphertext = crypt(
                    Cipher.ENCRYPT_MODE,
                    dek,
                    chunkNonce(noncePrefix, index),
                    chunkAad(headerDigest, index, read),
                    Arrays.copyOf(buffer, read));
                output.writeInt(read);
                output.writeInt(ciphertext.length);
                output.write(ciphertext);
                remaining -= read;
            }
            if (remaining != 0 || input.read() != -1) {
                throw new IOException("Source file changed while it was being encrypted");
            }

            byte[] digest = plaintextDigest.digest();
            byte[] encryptedDigest = crypt(
                Cipher.ENCRYPT_MODE,
                dek,
                chunkNonce(noncePrefix, info.chunkCount()),
                chunkAad(headerDigest, info.chunkCount(), HASH_LENGTH),
                digest);
            output.writeInt(encryptedDigest.length);
            output.write(encryptedDigest);
            output.flush();
            fileOutput.getChannel().force(true);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private BackupInfo decryptTo(Path backup, Path temporary, byte[] brk)
        throws IOException, GeneralSecurityException {
        try (var input = new DataInputStream(
                 new BufferedInputStream(Files.newInputStream(backup)));
             var fileOutput = new FileOutputStream(temporary.toFile());
             var output = new BufferedOutputStream(fileOutput)) {
            Header header = readHeader(input);
            byte[] dek = crypt(
                Cipher.DECRYPT_MODE,
                brk,
                header.wrapNonce(),
                header.baseHeader(),
                header.wrappedDek());
            if (dek.length != KEY_LENGTH) {
                Arrays.fill(dek, (byte) 0);
                throw new GeneralSecurityException("Invalid data encryption key length");
            }
            try {
                MessageDigest plaintextDigest = MessageDigest.getInstance("SHA-256");
                byte[] headerDigest = sha256(header.completeHeader());
                long written = 0;
                for (int index = 0; index < header.info().chunkCount(); index++) {
                    int expectedPlaintext = expectedChunkLength(header.info(), index);
                    int plaintextLength = input.readInt();
                    int ciphertextLength = input.readInt();
                    if (plaintextLength != expectedPlaintext
                        || ciphertextLength != plaintextLength + GCM_TAG_LENGTH) {
                        throw new GeneralSecurityException("Invalid encrypted chunk lengths");
                    }
                    byte[] ciphertext = readExact(input, ciphertextLength);
                    byte[] plaintext = crypt(
                        Cipher.DECRYPT_MODE,
                        dek,
                        chunkNonce(header.noncePrefix(), index),
                        chunkAad(headerDigest, index, plaintextLength),
                        ciphertext);
                    output.write(plaintext);
                    plaintextDigest.update(plaintext);
                    written += plaintext.length;
                }

                int encryptedHashLength = input.readInt();
                if (encryptedHashLength != ENCRYPTED_HASH_LENGTH) {
                    throw new GeneralSecurityException("Invalid encrypted hash length");
                }
                byte[] expectedDigest = crypt(
                    Cipher.DECRYPT_MODE,
                    dek,
                    chunkNonce(header.noncePrefix(), header.info().chunkCount()),
                    chunkAad(
                        headerDigest,
                        header.info().chunkCount(),
                        HASH_LENGTH),
                    readExact(input, encryptedHashLength));
                if (input.read() != -1 || written != header.info().plaintextLength()) {
                    throw new GeneralSecurityException("Invalid backup length");
                }
                byte[] actualDigest = plaintextDigest.digest();
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                    throw new GeneralSecurityException("Backup SHA-256 mismatch");
                }
                output.flush();
                fileOutput.getChannel().force(true);
                return header.info();
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        } catch (EOFException exception) {
            throw new GeneralSecurityException("Truncated backup", exception);
        }
    }

    private static Header readHeader(DataInputStream input)
        throws IOException, GeneralSecurityException {
        byte[] magic = input.readNBytes(MAGIC.length);
        int version = input.readUnsignedByte();
        int chunkSize = input.readInt();
        long plaintextLength = input.readLong();
        int chunkCount = input.readInt();
        byte[] wrapNonce = readExact(input, WRAP_NONCE_LENGTH);
        byte[] noncePrefix = readExact(input, NONCE_PREFIX_LENGTH);
        if (!Arrays.equals(magic, MAGIC) || version != VERSION) {
            throw new GeneralSecurityException("Unsupported backup format");
        }
        if (chunkSize < MIN_CHUNK_SIZE
            || chunkSize > MAX_CHUNK_SIZE
            || plaintextLength < 0
            || chunkCount != chunkCount(plaintextLength, chunkSize)) {
            throw new GeneralSecurityException("Invalid backup header");
        }
        BackupInfo info = new BackupInfo(version, chunkSize, plaintextLength, chunkCount);
        byte[] baseHeader = baseHeader(info, wrapNonce, noncePrefix);
        int wrappedDekLength = input.readInt();
        if (wrappedDekLength != WRAPPED_DEK_LENGTH) {
            throw new GeneralSecurityException("Invalid wrapped key length");
        }
        byte[] wrappedDek = readExact(input, wrappedDekLength);
        return new Header(
            info,
            wrapNonce,
            noncePrefix,
            wrappedDek,
            baseHeader,
            completeHeader(baseHeader, wrappedDek));
    }

    private static byte[] baseHeader(
        BackupInfo info,
        byte[] wrapNonce,
        byte[] noncePrefix
    ) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.write(MAGIC);
            output.writeByte(info.version());
            output.writeInt(info.chunkSize());
            output.writeLong(info.plaintextLength());
            output.writeInt(info.chunkCount());
            output.write(wrapNonce);
            output.write(noncePrefix);
        }
        return bytes.toByteArray();
    }

    private static byte[] completeHeader(byte[] baseHeader, byte[] wrappedDek)
        throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.write(baseHeader);
            output.writeInt(wrappedDek.length);
            output.write(wrappedDek);
        }
        return bytes.toByteArray();
    }

    private static byte[] crypt(
        int mode,
        byte[] key,
        byte[] nonce,
        byte[] aad,
        byte[] value
    ) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(value);
    }

    private static byte[] chunkNonce(byte[] prefix, int index) {
        return ByteBuffer.allocate(WRAP_NONCE_LENGTH)
            .put(prefix)
            .putInt(index)
            .array();
    }

    private static byte[] chunkAad(byte[] headerDigest, int index, int plaintextLength) {
        return ByteBuffer.allocate(headerDigest.length + Integer.BYTES * 2)
            .put(headerDigest)
            .putInt(index)
            .putInt(plaintextLength)
            .array();
    }

    private static int expectedChunkLength(BackupInfo info, int index) {
        long offset = (long) index * info.chunkSize();
        return (int) Math.min(info.chunkSize(), info.plaintextLength() - offset);
    }

    private static int chunkCount(long plaintextLength, int chunkSize) {
        long count = plaintextLength == 0
            ? 0
            : ((plaintextLength - 1) / chunkSize) + 1;
        if (count > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("Backup has too many chunks");
        }
        return (int) count;
    }

    private static int readFully(BufferedInputStream input, byte[] buffer, int length)
        throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(buffer, total, length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static byte[] readExact(DataInputStream input, int length)
        throws IOException, GeneralSecurityException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new GeneralSecurityException("Truncated backup");
        }
        return bytes;
    }

    private static byte[] sha256(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static void validateBrk(byte[] brk) {
        if (brk == null || brk.length != KEY_LENGTH) {
            throw new IllegalArgumentException("BRK must contain exactly 32 bytes");
        }
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    private static Path createTemporarySibling(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Destination directory does not exist");
        }
        return Files.createTempFile(
            parent,
            "." + absolute.getFileName() + ".",
            ".tmp");
    }

    private static void promoteAtomically(Path temporary, Path destination)
        throws IOException {
        try {
            Files.move(
                temporary,
                destination.toAbsolutePath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic file promotion is not supported", exception);
        }
    }

    public record BackupInfo(
        int version,
        int chunkSize,
        long plaintextLength,
        int chunkCount
    ) {
    }

    private record Header(
        BackupInfo info,
        byte[] wrapNonce,
        byte[] noncePrefix,
        byte[] wrappedDek,
        byte[] baseHeader,
        byte[] completeHeader
    ) {
    }
}
