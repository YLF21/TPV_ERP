package com.tpverp.backend.organization;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.template.DocumentTemplateType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreDocumentPrintConfigurationService {

    static final int MAX_LOGO_BYTES = 2 * 1024 * 1024;
    static final int MAX_LOGO_EDGE = 4096;
    static final long MAX_LOGO_PIXELS = 16_000_000L;

    private final CurrentOrganization organization;
    private final StoreDocumentPrintSettingsRepository settings;
    private final StoreDocumentLogoRepository logos;
    private final AuditService audit;
    private final Clock clock;

    public StoreDocumentPrintConfigurationService(
            CurrentOrganization organization,
            StoreDocumentPrintSettingsRepository settings,
            StoreDocumentLogoRepository logos,
            AuditService audit,
            Clock clock) {
        this.organization = organization;
        this.settings = settings;
        this.logos = logos;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Configuration configuration() {
        UUID storeId = organization.currentStore().getId();
        var value = settings.findById(storeId).orElse(null);
        return view(storeId, value);
    }

    @Transactional
    public Configuration updateObservations(String ticket, String invoice, String deliveryNote) {
        UUID storeId = organization.currentStore().getId();
        var value = settings.findById(storeId)
                .orElseGet(() -> new StoreDocumentPrintSettings(storeId));
        value.updateObservations(ticket, invoice, deliveryNote);
        settings.save(value);
        audit.record("STORE_DOCUMENT_PRINT_OBSERVATIONS_UPDATED", AuditResult.EXITO,
                Map.of("storeId", storeId.toString()));
        return view(storeId, value);
    }

    @Transactional
    public Configuration uploadLogo(byte[] content) {
        UUID storeId = organization.currentStore().getId();
        var validated = validate(content);
        String sha256 = sha256(validated.content());
        var logo = logos.findByStoreIdAndSha256(storeId, sha256)
                .orElseGet(() -> logos.save(new StoreDocumentLogo(
                        storeId, validated.contentType(), validated.content(), sha256,
                        Instant.now(clock))));
        var value = settings.findById(storeId)
                .orElseGet(() -> new StoreDocumentPrintSettings(storeId));
        value.useLogo(logo.getId());
        settings.save(value);
        audit.record("STORE_DOCUMENT_PRINT_LOGO_UPDATED", AuditResult.EXITO,
                Map.of("storeId", storeId.toString(), "sha256", logo.getSha256()));
        return view(storeId, value);
    }

    @Transactional
    public Configuration removeLogo() {
        UUID storeId = organization.currentStore().getId();
        var value = settings.findById(storeId)
                .orElseGet(() -> new StoreDocumentPrintSettings(storeId));
        value.useLogo(null);
        settings.save(value);
        audit.record("STORE_DOCUMENT_PRINT_LOGO_REMOVED", AuditResult.EXITO,
                Map.of("storeId", storeId.toString()));
        return view(storeId, value);
    }

    @Transactional(readOnly = true)
    public Presentation presentation(DocumentTemplateType type) {
        UUID storeId = organization.currentStore().getId();
        var value = settings.findById(storeId).orElse(null);
        if (value == null) return new Presentation(null, null);
        String observations = switch (Objects.requireNonNull(type, "type")) {
            case TICKET -> value.getTicketObservations();
            case FACTURA_VENTA -> value.getInvoiceObservations();
            case ALBARAN_VENTA -> value.getDeliveryNoteObservations();
        };
        LogoReference logo = value.getLogoId() == null ? null
                : logos.findByIdAndStoreId(value.getLogoId(), storeId)
                        .map(item -> new LogoReference(
                                item.getId(), item.getContentType(), item.getSha256()))
                        .orElseThrow(() -> new IllegalStateException(
                                "document_print_logo_not_found"));
        return new Presentation(observations, logo);
    }

    @Transactional(readOnly = true)
    public String logoDataUri(UUID storeId, LogoReference reference) {
        if (reference == null) return null;
        var logo = logos.findByIdAndStoreId(reference.id(), storeId)
                .orElseThrow(() -> new IllegalStateException("document_print_logo_not_found"));
        if (!logo.getContentType().equals(reference.contentType())
                || !logo.getSha256().equals(reference.sha256())) {
            throw new IllegalStateException("document_print_logo_reference_mismatch");
        }
        return dataUri(logo);
    }

    private Configuration view(UUID storeId, StoreDocumentPrintSettings value) {
        Logo logo = value == null || value.getLogoId() == null ? null
                : logos.findByIdAndStoreId(value.getLogoId(), storeId)
                        .map(item -> new Logo(item.getId(), item.getContentType(),
                                item.getSha256(), item.getCreatedAt(), dataUri(item)))
                        .orElseThrow(() -> new IllegalStateException(
                                "document_print_logo_not_found"));
        return new Configuration(
                storeId,
                logo,
                value == null ? null : value.getTicketObservations(),
                value == null ? null : value.getInvoiceObservations(),
                value == null ? null : value.getDeliveryNoteObservations());
    }

    private static String dataUri(StoreDocumentLogo logo) {
        return "data:" + logo.getContentType() + ";base64,"
                + Base64.getEncoder().encodeToString(logo.getContent());
    }

    static ValidatedLogo validate(byte[] value) {
        if (value == null || value.length == 0 || value.length > MAX_LOGO_BYTES) {
            throw new IllegalArgumentException("document_print_logo_size_invalid");
        }
        byte[] content = java.util.Arrays.copyOf(value, value.length);
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(content))) {
            if (input == null) throw new IllegalArgumentException("document_print_logo_invalid");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("document_print_logo_invalid");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String contentType = switch (format) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw new IllegalArgumentException(
                            "document_print_logo_type_invalid");
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_LOGO_EDGE
                        || height > MAX_LOGO_EDGE
                        || (long) width * height > MAX_LOGO_PIXELS) {
                    throw new IllegalArgumentException("document_print_logo_dimensions_invalid");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new IllegalArgumentException("document_print_logo_invalid");
                }
                return new ValidatedLogo(contentType, content, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("document_print_logo_invalid", error);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Configuration(
            UUID storeId,
            Logo logo,
            String ticketObservations,
            String invoiceObservations,
            String deliveryNoteObservations) {
    }

    public record Logo(UUID id, String contentType, String sha256,
            Instant createdAt, String dataUri) {
    }

    public record Presentation(String observations, LogoReference logo) {
    }

    public record LogoReference(UUID id, String contentType, String sha256) {
        public LogoReference {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contentType, "contentType");
            if (!contentType.equals("image/png") && !contentType.equals("image/jpeg")) {
                throw new IllegalArgumentException("document_print_logo_reference_invalid");
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("document_print_logo_reference_invalid");
            }
        }
    }

    record ValidatedLogo(String contentType, byte[] content, int width, int height) {
    }
}
