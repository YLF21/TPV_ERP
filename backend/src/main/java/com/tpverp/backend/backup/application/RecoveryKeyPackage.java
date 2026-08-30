package com.tpverp.backend.backup.application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public final class RecoveryKeyPackage {

    private static final byte[] MAGIC = {'T', 'P', 'V', 'R'};
    private static final int VERSION = 1;
    private static final int VERSION_V2 = 2;
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int BRK_LENGTH = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final int ENCRYPTED_BRK_LENGTH = BRK_LENGTH + (GCM_TAG_BITS / 8);

    private final SecureRandom random;

    public RecoveryKeyPackage() {
        this(new SecureRandom());
    }

    RecoveryKeyPackage(SecureRandom random) {
        this.random = random;
    }

    public byte[] create(char[] adminPassword, int iterations) throws GeneralSecurityException {
        validateIterations(iterations);
        byte[] brk = randomBytes(BRK_LENGTH);
        try {
            return protect(brk, adminPassword, iterations, VERSION);
        } finally {
            Arrays.fill(brk, (byte) 0);
        }
    }

    /** Creates the current recovery format, deliberately independent of ADMIN. */
    public byte[] createV2(char[] recoverySecret, int iterations) throws GeneralSecurityException {
        validateIterations(iterations);
        validateStrongSecret(recoverySecret);
        byte[] brk = randomBytes(BRK_LENGTH);
        try {
            return protect(brk, recoverySecret, iterations, VERSION_V2);
        } finally {
            Arrays.fill(brk, (byte) 0);
        }
    }

    public byte[] createV2ForKey(byte[] brk, char[] recoverySecret, int iterations)
            throws GeneralSecurityException {
        validateIterations(iterations);
        validateStrongSecret(recoverySecret);
        if (brk == null || brk.length != BRK_LENGTH) {
            throw new IllegalArgumentException("La clave de backup no tiene un tamaño válido");
        }
        return protect(brk, recoverySecret, iterations, VERSION_V2);
    }

    /** Generates a one-time 256-bit recovery code; callers must not log it. */
    public char[] generateRecoveryCode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32)).toCharArray();
    }

    public byte[] open(byte[] recoveryPackage, char[] adminPassword)
        throws GeneralSecurityException {
        Parsed parsed = parse(recoveryPackage);
        SecretKeySpec passwordKey = deriveKey(adminPassword, parsed.salt(), parsed.info().iterations());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, passwordKey, new GCMParameterSpec(GCM_TAG_BITS, parsed.nonce()));
        cipher.updateAAD(parsed.header());
        byte[] brk = cipher.doFinal(parsed.encryptedBrk());
        if (brk.length != BRK_LENGTH) {
            Arrays.fill(brk, (byte) 0);
            throw new GeneralSecurityException("Invalid recovery key length");
        }
        return brk;
    }

    /** Reads v2 and legacy v1 packages. New packages must always be v2. */
    public byte[] openAny(byte[] recoveryPackage, char[] recoverySecret)
        throws GeneralSecurityException {
        return openParsed(recoveryPackage, recoverySecret);
    }

    public byte[] rewrap(
        byte[] recoveryPackage,
        char[] currentAdminPassword,
        char[] newAdminPassword,
        int newIterations
    ) throws GeneralSecurityException {
        validateIterations(newIterations);
        if (inspect(recoveryPackage).version() != VERSION) {
            throw new GeneralSecurityException("Los paquetes de recuperación v2 no se reenvuelven con ADMIN");
        }
        byte[] brk = open(recoveryPackage, currentAdminPassword);
        try {
            return protect(brk, newAdminPassword, newIterations, VERSION);
        } finally {
            Arrays.fill(brk, (byte) 0);
        }
    }

    public Info inspect(byte[] recoveryPackage) throws GeneralSecurityException {
        return parse(recoveryPackage).info();
    }

    private byte[] protect(byte[] brk, char[] password, int iterations, int version)
        throws GeneralSecurityException {
        byte[] salt = randomBytes(SALT_LENGTH);
        byte[] nonce = randomBytes(NONCE_LENGTH);
        byte[] header = header(iterations, salt, nonce, ENCRYPTED_BRK_LENGTH, version);
        SecretKeySpec passwordKey = deriveKey(password, salt, iterations);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(header);
        byte[] encryptedBrk = cipher.doFinal(brk);

        byte[] result = Arrays.copyOf(header, header.length + encryptedBrk.length);
        System.arraycopy(encryptedBrk, 0, result, header.length, encryptedBrk.length);
        return result;
    }

    private byte[] openParsed(byte[] recoveryPackage, char[] secret)
        throws GeneralSecurityException {
        Parsed parsed = parse(recoveryPackage);
        SecretKeySpec passwordKey = deriveKey(secret, parsed.salt(), parsed.info().iterations());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, passwordKey, new GCMParameterSpec(GCM_TAG_BITS, parsed.nonce()));
        cipher.updateAAD(parsed.header());
        byte[] brk = cipher.doFinal(parsed.encryptedBrk());
        if (brk.length != BRK_LENGTH) {
            Arrays.fill(brk, (byte) 0);
            throw new GeneralSecurityException("Invalid recovery key length");
        }
        return brk;
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations)
        throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Admin password must not be empty");
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
            try {
                return new SecretKeySpec(encoded, "AES");
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static Parsed parse(byte[] encoded) throws GeneralSecurityException {
        if (encoded == null) {
            throw new GeneralSecurityException("Recovery package is required");
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            int version = input.readUnsignedByte();
            int iterations = input.readInt();
            int saltLength = input.readUnsignedByte();
            int nonceLength = input.readUnsignedByte();
            int encryptedLength = input.readInt();
            validateHeader(magic, version, iterations, saltLength, nonceLength, encryptedLength);

            byte[] salt = input.readNBytes(saltLength);
            byte[] nonce = input.readNBytes(nonceLength);
            int headerLength = encoded.length - input.available();
            byte[] encryptedBrk = input.readNBytes(encryptedLength);
            if (salt.length != saltLength
                || nonce.length != nonceLength
                || encryptedBrk.length != encryptedLength
                || input.available() != 0) {
                throw new GeneralSecurityException("Truncated or trailing recovery package data");
            }
            byte[] header = Arrays.copyOf(encoded, headerLength);
            return new Parsed(
                new Info(version, iterations, saltLength, nonceLength),
                salt,
                nonce,
                encryptedBrk,
                header);
        } catch (IOException | RuntimeException exception) {
            throw new GeneralSecurityException("Invalid recovery package", exception);
        }
    }

    private static byte[] header(
        int iterations,
        byte[] salt,
        byte[] nonce,
        int encryptedLength, int version
    ) throws GeneralSecurityException {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.write(MAGIC);
                output.writeByte(version);
                output.writeInt(iterations);
                output.writeByte(salt.length);
                output.writeByte(nonce.length);
                output.writeInt(encryptedLength);
                output.write(salt);
                output.write(nonce);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new GeneralSecurityException("Cannot encode recovery package", exception);
        }
    }

    private static void validateHeader(
        byte[] magic,
        int version,
        int iterations,
        int saltLength,
        int nonceLength,
        int encryptedLength
    ) throws GeneralSecurityException {
        if (!Arrays.equals(magic, MAGIC) || (version != VERSION && version != VERSION_V2)) {
            throw new GeneralSecurityException("Unsupported recovery package");
        }
        try {
            validateIterations(iterations);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Invalid recovery package iterations", exception);
        }
        if (saltLength != SALT_LENGTH
            || nonceLength != NONCE_LENGTH
            || encryptedLength != ENCRYPTED_BRK_LENGTH) {
            throw new GeneralSecurityException("Invalid recovery package lengths");
        }
    }

    private static void validateIterations(int iterations) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                "PBKDF2 iterations must be between 100000 and 10000000");
        }
    }

    private static void validateStrongSecret(char[] secret) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("La clave de recuperación debe tener al menos 16 caracteres");
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    public record Info(int version, int iterations, int saltLength, int nonceLength) {
    }

    private record Parsed(
        Info info,
        byte[] salt,
        byte[] nonce,
        byte[] encryptedBrk,
        byte[] header
    ) {
    }
}
