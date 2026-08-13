package com.tpverp.backend.document.template;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTemplateCatalogService {

    private final DocumentTemplateRepository templates;
    private final StoreRepository stores;
    private final CurrentOrganization organization;
    private final DocumentTemplateResolver resolver;
    private final AuditService audit;
    private final Clock clock;

    public DocumentTemplateCatalogService(
            DocumentTemplateRepository templates,
            StoreRepository stores,
            CurrentOrganization organization,
            DocumentTemplateResolver resolver,
            AuditService audit,
            Clock clock) {
        this.templates = templates;
        this.stores = stores;
        this.organization = organization;
        this.resolver = resolver;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CatalogView currentStoreCatalog(DocumentTemplateType type) {
        return currentStoreCatalog(type, DocumentTemplateFormat.defaultFor(type));
    }

    @Transactional(readOnly = true)
    public CatalogView currentStoreCatalog(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        var store = organization.currentStore();
        return new CatalogView(
                resolver.resolve(store, type, format),
                templates.findAllForStore(store.getId()).stream()
                        .filter(template -> template.getType() == type
                                && template.getFormat() == format)
                        .map(TemplateView::from)
                        .toList());
    }

    @Transactional
    public TemplateView registerCurrentStoreDraft(
            DocumentTemplateType type,
            DocumentTemplateFormat format,
            String code,
            String name) {
        var currentStore = organization.currentStore();
        var store = stores.findByIdForUpdate(currentStore.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "document_template_store_not_found"));
        String normalizedCode = DocumentTemplate.normalizeCode(code);
        int nextVersion = java.util.Optional.ofNullable(
                        templates.findMaxVersionForStore(store.getId(), normalizedCode))
                .orElse(0) + 1;
        var template = DocumentTemplate.storeDraft(
                store,
                type,
                format,
                normalizedCode,
                nextVersion,
                name,
                currentUserId(),
                clock.instant());
        var saved = templates.saveAndFlush(template);
        audit.record("DOCUMENT_TEMPLATE_DRAFT_REGISTERED", AuditResult.EXITO,
                auditDetails(saved));
        return TemplateView.from(saved);
    }

    @Transactional
    public TemplateView registerCurrentStoreDraft(
            DocumentTemplateType type,
            String code,
            String name) {
        return registerCurrentStoreDraft(
                type, DocumentTemplateFormat.defaultFor(type), code, name);
    }

    /**
     * Internal boundary for a trusted validator. Activating a new version retires only the
     * previous template for the same store and document type.
     */
    @Transactional
    public TemplateView activateValidatedCurrentStoreTemplate(UUID templateId) {
        var template = currentStoreTemplateForUpdate(templateId);
        if (template.getStatus() == DocumentTemplateStatus.ACTIVE) {
            return TemplateView.from(template);
        }
        var now = clock.instant();
        templates.findActiveStoreTemplateForUpdate(
                        template.getStore().getId(), template.getType(), template.getFormat())
                .filter(active -> !active.getId().equals(template.getId()))
                .ifPresent(active -> {
                    active.retire(now);
                    templates.saveAndFlush(active);
                    audit.record("DOCUMENT_TEMPLATE_RETIRED", AuditResult.EXITO,
                            auditDetails(active));
                });
        template.activate(now);
        var saved = templates.saveAndFlush(template);
        audit.record("DOCUMENT_TEMPLATE_ACTIVATED", AuditResult.EXITO,
                auditDetails(saved));
        return TemplateView.from(saved);
    }

    private DocumentTemplate currentStoreTemplateForUpdate(UUID templateId) {
        var store = organization.currentStore();
        return templates.findStoreTemplateForUpdate(
                        templateId, store.getEmpresa().getId(), store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "document_template_not_found"));
    }

    private UUID currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        try {
            return organization.currentUser(authentication).getId();
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private static LinkedHashMap<String, Object> auditDetails(DocumentTemplate template) {
        var details = new LinkedHashMap<String, Object>();
        details.put("templateId", template.getId().toString());
        details.put("scope", template.getScope().name());
        details.put("type", template.getType().name());
        details.put("format", template.getFormat().name());
        details.put("code", template.getCode());
        details.put("templateVersion", template.getTemplateVersion());
        details.put("status", template.getStatus().name());
        return details;
    }

    public record CatalogView(
            ResolvedDocumentTemplate effective,
            List<TemplateView> storeTemplates) {
    }

    public record TemplateView(
            UUID id,
            DocumentTemplateType type,
            DocumentTemplateFormat format,
            DocumentTemplateScope scope,
            String code,
            int version,
            String name,
            DocumentTemplateStatus status,
            Integer schemaVersion,
            String sha256,
            UUID createdByUserId,
            Instant createdAt,
            Instant validatedAt,
            Instant activatedAt,
            Instant retiredAt) {

        static TemplateView from(DocumentTemplate template) {
            return new TemplateView(
                    template.getId(), template.getType(), template.getFormat(), template.getScope(),
                    template.getCode(), template.getTemplateVersion(), template.getName(),
                    template.getStatus(), template.getSchemaVersion(), template.getSha256(),
                    template.getCreatedByUserId(), template.getCreatedAt(),
                    template.getValidatedAt(), template.getActivatedAt(),
                    template.getRetiredAt());
        }
    }
}
