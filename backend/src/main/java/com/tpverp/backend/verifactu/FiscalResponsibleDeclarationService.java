package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Resolves the responsible declaration from the release artifact itself.
 *
 * <p>The development artifact intentionally has no declaration resource. A
 * resource is only considered publishable when it is a PDF and its digest is
 * the digest declared by the immutable release manifest.</p>
 */
@Service
public class FiscalResponsibleDeclarationService {

    static final String DOWNLOAD_URL = "/api/v1/fiscal/responsible-declaration/content";
    static final long MAX_DECLARATION_SIZE_BYTES = 25L * 1024L * 1024L;
    private static final String RESOURCE_PREFIX = "META-INF/fiscal/declaracion-responsable-";
    private static final int READ_BUFFER_SIZE = 8 * 1024;

    private final FiscalRuntimeProperties runtime;
    private final FiscalReleaseManifest manifest;
    private final Resource injectedResource;
    private final java.util.Optional<LoadedDeclaration> loadedDeclaration;

    @Autowired
    public FiscalResponsibleDeclarationService(FiscalRuntimeProperties runtime) {
        this(runtime, null);
    }

    /**
     * Package-private constructor used by focused tests to provide a resource
     * without adding a large PDF fixture to the test classpath.
     */
    FiscalResponsibleDeclarationService(FiscalRuntimeProperties runtime, Resource injectedResource) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.manifest = runtime.releaseManifest();
        this.injectedResource = injectedResource;
        // The declaration is an immutable release artifact. Load and verify it
        // once so status/content cannot repeatedly read or re-hash the resource.
        this.loadedDeclaration = loadAvailable();
        if (runtime.runtimeClass() == FiscalRuntimeClass.REAL
                && runtime.endpointEnvironment() == FiscalEndpointEnvironment.PRODUCTION) {
            // Production must never start with an absent or tampered declaration.
            loadedDeclaration.orElseThrow(() -> new IllegalStateException(
                    "La declaracion responsable PDF no existe o no coincide con el manifiesto"));
        }
    }

    public ResponsibleDeclarationStatus status() {
        return loadedDeclaration.map(declaration -> new ResponsibleDeclarationStatus(
                        "AVAILABLE", manifest.systemVersion(), manifest.releaseId(),
                        declaration.fileName(), "application/pdf", (long) declaration.size(),
                        declaration.sha256(), declaration.issuedAt(), DOWNLOAD_URL))
                .orElseGet(() -> unavailableStatus());
    }

    public LoadedDeclaration content() {
        return loadedDeclaration.orElseThrow(() -> new DeclarationUnavailableException(
                "La declaracion responsable PDF no esta disponible"));
    }

    private java.util.Optional<LoadedDeclaration> loadAvailable() {
        if (manifest.declarationHash() == null) {
            return java.util.Optional.empty();
        }
        var resource = resourceFor(manifest);
        if (resource == null || !resource.exists() || !resource.isReadable()) {
            return java.util.Optional.empty();
        }
        final long contentLength;
        try {
            contentLength = resource.contentLength();
        } catch (IOException exception) {
            return java.util.Optional.empty();
        }
        if (contentLength > MAX_DECLARATION_SIZE_BYTES || contentLength < -1) {
            return java.util.Optional.empty();
        }
        try (InputStream input = resource.getInputStream()) {
            var bytes = readAtMost(input, contentLength);
            if (bytes == null) {
                return java.util.Optional.empty();
            }
            if (!isPdf(bytes)) {
                return java.util.Optional.empty();
            }
            var sha256 = sha256(bytes);
            if (!manifest.declarationHash().equalsIgnoreCase(sha256)) {
                return java.util.Optional.empty();
            }
            var fileName = resource.getFilename();
            if (fileName == null || fileName.isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new LoadedDeclaration(bytes, fileName, sha256,
                    null, resource));
        } catch (IOException exception) {
            return java.util.Optional.empty();
        }
    }

    private Resource resourceFor(FiscalReleaseManifest manifest) {
        if (injectedResource != null) {
            return injectedResource;
        }
        // The release version is part of the classpath path; this prevents a
        // generic/unversioned declaration from being accidentally promoted.
        var path = RESOURCE_PREFIX + safePathPart(manifest.systemVersion()) + ".pdf";
        return new ClassPathResource(path);
    }

    /**
     * Reads a resource without ever retaining more than the bundle verifier's
     * 25 MiB declaration limit. A known content length is allocated exactly;
     * unknown-length resources are accumulated in fixed-size chunks and are
     * rejected as soon as the first byte over the limit is observed.
     */
    private static byte[] readAtMost(InputStream input, long contentLength) throws IOException {
        if (contentLength >= 0) {
            return readKnownSize(input, (int) contentLength);
        }
        return readUnknownSize(input);
    }

    private static byte[] readKnownSize(InputStream input, int contentLength) throws IOException {
        var bytes = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = input.read(bytes, offset, contentLength - offset);
            if (read < 0) {
                return null;
            }
            if (read == 0) {
                int one = input.read();
                if (one < 0) {
                    return null;
                }
                bytes[offset++] = (byte) one;
            } else {
                offset += read;
            }
        }
        // Do not trust a stale/misreported length: reject trailing bytes too.
        return input.read() < 0 ? bytes : null;
    }

    private static byte[] readUnknownSize(InputStream input) throws IOException {
        var chunks = new ArrayList<byte[]>();
        int total = 0;
        while (true) {
            int chunkSize = (int) Math.min(READ_BUFFER_SIZE,
                    MAX_DECLARATION_SIZE_BYTES - total + 1);
            var chunk = new byte[chunkSize];
            int read = input.read(chunk, 0, chunkSize);
            if (read < 0) {
                return join(chunks, total);
            }
            if (read == 0) {
                int one = input.read();
                if (one < 0) {
                    return join(chunks, total);
                }
                if (total >= MAX_DECLARATION_SIZE_BYTES) {
                    return null;
                }
                chunk[0] = (byte) one;
                read = 1;
            }
            if ((long) total + read > MAX_DECLARATION_SIZE_BYTES) {
                return null;
            }
            chunks.add(read == chunk.length ? chunk : Arrays.copyOf(chunk, read));
            total += read;
        }
    }

    private static byte[] join(ArrayList<byte[]> chunks, int total) {
        var bytes = new byte[total];
        int offset = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        return bytes;
    }

    private ResponsibleDeclarationStatus unavailableStatus() {
        return new ResponsibleDeclarationStatus("UNAVAILABLE", manifest.systemVersion(),
                manifest.releaseId(), null, null, null, null, null, DOWNLOAD_URL);
    }

    private static String safePathPart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isPdf(byte[] bytes) {
        if (bytes.length < 5) {
            return false;
        }
        return bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    public record ResponsibleDeclarationStatus(
            String status,
            String systemVersion,
            String releaseId,
            String fileName,
            String contentType,
            Long size,
            String sha256,
            Instant issuedAt,
            String downloadUrl) {
    }

    public record LoadedDeclaration(
            byte[] bytes,
            String fileName,
            String sha256,
            Instant issuedAt,
            Resource resource) {

        public LoadedDeclaration {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public int size() {
            return bytes.length;
        }
    }

    public static class DeclarationUnavailableException extends RuntimeException {
        public DeclarationUnavailableException(String message) {
            super(message);
        }
    }
}
