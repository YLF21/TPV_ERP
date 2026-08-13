package com.tpverp.backend.document.template;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "plantilla_documento")
public class DocumentTemplate {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empresa_id")
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tienda_id")
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 32)
    private DocumentTemplateType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "ambito", nullable = false, length = 16)
    private DocumentTemplateScope scope;

    @Column(name = "codigo", nullable = false, length = 80)
    private String code;

    @Column(name = "version_plantilla", nullable = false)
    private int templateVersion;

    @Column(name = "nombre", nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private DocumentTemplateStatus status;

    @Column(name = "schema_version")
    private Integer schemaVersion;

    @Column(name = "artifact_reference", length = 512)
    private String artifactReference;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "creada_por_usuario_id")
    private UUID createdByUserId;

    @Column(name = "creada_en", nullable = false)
    private Instant createdAt;

    @Column(name = "validada_en")
    private Instant validatedAt;

    @Column(name = "activada_en")
    private Instant activatedAt;

    @Column(name = "retirada_en")
    private Instant retiredAt;

    @Version
    private Long version;

    protected DocumentTemplate() {
    }

    private DocumentTemplate(
            Company company,
            Store store,
            DocumentTemplateType type,
            DocumentTemplateScope scope,
            String code,
            int templateVersion,
            String name,
            UUID createdByUserId,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.company = company;
        this.store = store;
        this.type = Objects.requireNonNull(type, "type");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.code = normalizeCode(code);
        if (templateVersion <= 0) {
            throw new IllegalArgumentException("document_template_version_invalid");
        }
        this.templateVersion = templateVersion;
        this.name = required(name, "document_template_name_required", 160);
        this.status = DocumentTemplateStatus.DRAFT;
        this.createdByUserId = createdByUserId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        validateScope();
    }

    static DocumentTemplate storeDraft(
            Store store,
            DocumentTemplateType type,
            String code,
            int templateVersion,
            String name,
            UUID createdByUserId,
            Instant createdAt) {
        Objects.requireNonNull(store, "store");
        return new DocumentTemplate(store.getEmpresa(), store, type, DocumentTemplateScope.STORE,
                code, templateVersion, name, createdByUserId, createdAt);
    }

    static DocumentTemplate companyDraft(
            Company company,
            DocumentTemplateType type,
            String code,
            int templateVersion,
            String name,
            UUID createdByUserId,
            Instant createdAt) {
        Objects.requireNonNull(company, "company");
        return new DocumentTemplate(company, null, type, DocumentTemplateScope.COMPANY,
                code, templateVersion, name, createdByUserId, createdAt);
    }

    static DocumentTemplate systemDraft(
            DocumentTemplateType type,
            String code,
            int templateVersion,
            String name,
            UUID createdByUserId,
            Instant createdAt) {
        return new DocumentTemplate(null, null, type, DocumentTemplateScope.SYSTEM,
                code, templateVersion, name, createdByUserId, createdAt);
    }

    void validateArtifact(
            int schemaVersion,
            String artifactReference,
            String sha256,
            Instant validatedAt) {
        if (status != DocumentTemplateStatus.DRAFT) {
            throw new IllegalStateException("document_template_not_draft");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("document_template_schema_version_invalid");
        }
        String normalizedHash = required(sha256, "document_template_sha256_required", 64)
                .toLowerCase(Locale.ROOT);
        if (!normalizedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("document_template_sha256_invalid");
        }
        this.schemaVersion = schemaVersion;
        this.artifactReference = required(
                artifactReference, "document_template_artifact_reference_required", 512);
        this.sha256 = normalizedHash;
        this.validatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
        this.status = DocumentTemplateStatus.VALIDATED;
    }

    void activate(Instant activatedAt) {
        if (status == DocumentTemplateStatus.ACTIVE) {
            return;
        }
        if (status != DocumentTemplateStatus.VALIDATED) {
            throw new IllegalStateException("document_template_not_validated");
        }
        this.activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        this.status = DocumentTemplateStatus.ACTIVE;
    }

    void retire(Instant retiredAt) {
        if (status == DocumentTemplateStatus.RETIRED) {
            return;
        }
        if (status != DocumentTemplateStatus.ACTIVE) {
            throw new IllegalStateException("document_template_not_active");
        }
        this.retiredAt = Objects.requireNonNull(retiredAt, "retiredAt");
        this.status = DocumentTemplateStatus.RETIRED;
    }

    private void validateScope() {
        boolean valid = switch (scope) {
            case SYSTEM -> company == null && store == null;
            case COMPANY -> company != null && store == null;
            case STORE -> company != null && store != null
                    && store.getEmpresa().getId().equals(company.getId());
        };
        if (!valid) {
            throw new IllegalArgumentException("document_template_scope_invalid");
        }
    }

    static String normalizeCode(String value) {
        String code = required(value, "document_template_code_required", 80)
                .toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9_]{2,79}")) {
            throw new IllegalArgumentException("document_template_code_invalid");
        }
        if (code.matches(".*_V[0-9]+$")) {
            throw new IllegalArgumentException("document_template_code_must_not_include_version");
        }
        return code;
    }

    private static String required(String value, String error, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(error);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(error);
        }
        return normalized;
    }

    public UUID getId() { return id; }
    public Company getCompany() { return company; }
    public Store getStore() { return store; }
    public DocumentTemplateType getType() { return type; }
    public DocumentTemplateScope getScope() { return scope; }
    public String getCode() { return code; }
    public int getTemplateVersion() { return templateVersion; }
    public String getName() { return name; }
    public DocumentTemplateStatus getStatus() { return status; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public String getArtifactReference() { return artifactReference; }
    public String getSha256() { return sha256; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getValidatedAt() { return validatedAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getRetiredAt() { return retiredAt; }
}
