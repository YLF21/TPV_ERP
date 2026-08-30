package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.springframework.core.io.AbstractResource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class FiscalResponsibleDeclarationServiceTest {

    private static final byte[] TEST_PDF = ("%PDF-1.4\nfake test declaration\n%%EOF\n")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void springCreatesTheServiceWithTheRuntimeConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(FiscalRuntimeProperties.class,
                    () -> runtime(hash(TEST_PDF), "TEST"));
            context.registerBean(FiscalResponsibleDeclarationService.class);
            context.refresh();

            assertThat(context.getBean(FiscalResponsibleDeclarationService.class)).isNotNull();
        }
    }

    @Test
    void servesOnlyTheVersionedPdfAndReportsItsDigest() {
        var service = new FiscalResponsibleDeclarationService(runtime(hash(TEST_PDF), "TEST"));

        var status = service.status();

        assertThat(status.status()).isEqualTo("AVAILABLE");
        assertThat(status.systemVersion()).isEqualTo("TEST");
        assertThat(status.releaseId()).isEqualTo("release-test");
        assertThat(status.fileName()).isEqualTo("declaracion-responsable-TEST.pdf");
        assertThat(status.contentType()).isEqualTo("application/pdf");
        assertThat(status.size()).isEqualTo((long) TEST_PDF.length);
        assertThat(status.sha256()).isEqualTo(hash(TEST_PDF));
        assertThat(service.content().bytes()).containsExactly(TEST_PDF);
    }

    @Test
    void loadsAndVerifiesTheImmutableResourceOnlyOncePerInstance() {
        var resource = new CountingResource(TEST_PDF);
        var service = new FiscalResponsibleDeclarationService(
                runtime(hash(TEST_PDF), "TEST"), resource);

        service.status();
        service.status();
        service.content();
        service.content();

        assertThat(resource.openCount).isEqualTo(1);
    }

    @Test
    void returnsDefensiveCopiesOfTheCachedPdfBytes() {
        var service = new FiscalResponsibleDeclarationService(
                runtime(hash(TEST_PDF), "TEST"), new CountingResource(TEST_PDF));

        var returned = service.content().bytes();
        returned[0] = 'X';

        assertThat(service.content().bytes()).containsExactly(TEST_PDF);
    }

    @Test
    void rejectsUnknownLengthResourcesAsSoonAsTheyExceedThe25MiBLimit() {
        var resource = new GeneratedOversizedResource();
        var service = new FiscalResponsibleDeclarationService(
                runtime(hash(TEST_PDF), "TEST"), resource);

        assertThat(service.status().status()).isEqualTo("UNAVAILABLE");
        assertThat(resource.openCount).isEqualTo(1);
        assertThat(resource.bytesRead).isEqualTo(
                FiscalResponsibleDeclarationService.MAX_DECLARATION_SIZE_BYTES + 1);
    }

    @Test
    void missingPdfIsUnavailableAndNeverReturnsAPlaceholder() {
        var service = new FiscalResponsibleDeclarationService(runtime(hash(TEST_PDF), "MISSING"));

        assertThat(service.status().status()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(service::content)
                .isInstanceOf(FiscalResponsibleDeclarationService.DeclarationUnavailableException.class);
    }

    @Test
    void hashMismatchIsUnavailable() {
        var service = new FiscalResponsibleDeclarationService(runtime("0".repeat(64), "TEST"));

        assertThat(service.status().status()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(service::content)
                .isInstanceOf(FiscalResponsibleDeclarationService.DeclarationUnavailableException.class);
    }

    private static FiscalRuntimeProperties runtime(String declarationHash, String version) {
        var properties = new Properties();
        properties.setProperty("release.id", "release-test");
        properties.setProperty("system.version", version);
        properties.setProperty("capability", "DUAL");
        properties.setProperty("schema.version", "V229");
        properties.setProperty("declaration.hash", declarationHash);
        var manifest = FiscalReleaseManifest.from(properties);
        return new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"), manifest);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class CountingResource extends AbstractResource {
        private final byte[] bytes;
        private int openCount;

        private CountingResource(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            openCount++;
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public String getFilename() {
            return "declaracion-responsable-TEST.pdf";
        }

        @Override
        public String getDescription() {
            return "counting test resource";
        }
    }

    private static final class GeneratedOversizedResource extends AbstractResource {
        private int openCount;
        private long bytesRead;

        @Override
        public InputStream getInputStream() {
            openCount++;
            return new InputStream() {
                private long position;
                private long remaining = FiscalResponsibleDeclarationService.MAX_DECLARATION_SIZE_BYTES + 1;

                @Override
                public int read(byte[] target, int offset, int length) {
                    if (remaining == 0) {
                        return -1;
                    }
                    int count = (int) Math.min((long) length, remaining);
                    for (int index = 0; index < count; index++) {
                        target[offset + index] = (byte) pdfHeaderByte(position + index);
                    }
                    position += count;
                    remaining -= count;
                    bytesRead += count;
                    return count;
                }

                @Override
                public int read() {
                    if (remaining == 0) {
                        return -1;
                    }
                    int value = pdfHeaderByte(position);
                    position++;
                    remaining--;
                    bytesRead++;
                    return value;
                }

                private int pdfHeaderByte(long index) {
                    return index < 5 ? "%PDF-".charAt((int) index) : 0;
                }
            };
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public String getFilename() {
            return "declaracion-responsable-TEST.pdf";
        }

        @Override
        public String getDescription() {
            return "generated oversized test resource";
        }
    }
}
