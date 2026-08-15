package com.tpverp.backend.document.template;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentTemplateResolver {

    private final DocumentTemplateRepository templates;
    private final CurrentOrganization organization;

    public DocumentTemplateResolver(
            DocumentTemplateRepository templates,
            CurrentOrganization organization) {
        this.templates = templates;
        this.organization = organization;
    }

    @Transactional(readOnly = true,
            noRollbackFor = DocumentTemplateRequiredException.class)
    public ResolvedDocumentTemplate resolve(DocumentTemplateType type) {
        return resolve(type, DocumentTemplateFormat.defaultFor(type));
    }

    @Transactional(readOnly = true,
            noRollbackFor = DocumentTemplateRequiredException.class)
    public ResolvedDocumentTemplate resolve(
            DocumentTemplateType type, DocumentTemplateFormat format) {
        return resolve(organization.currentStore(), type, format);
    }

    @Transactional(readOnly = true,
            noRollbackFor = DocumentTemplateRequiredException.class)
    public ResolvedDocumentTemplate resolve(Store store, DocumentTemplateType type) {
        return resolve(store, type, DocumentTemplateFormat.defaultFor(type));
    }

    @Transactional(readOnly = true,
            noRollbackFor = DocumentTemplateRequiredException.class)
    public ResolvedDocumentTemplate resolve(
            Store store, DocumentTemplateType type, DocumentTemplateFormat format) {
        return findEffective(store, type, format)
                .orElseThrow(() -> new DocumentTemplateRequiredException(type, format));
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedDocumentTemplate> findEffective(
            Store store, DocumentTemplateType type, DocumentTemplateFormat format) {
        var storeTemplate = templates.findActiveForStore(store.getId(), type, format);
        if (storeTemplate.isPresent()) {
            return storeTemplate.map(ResolvedDocumentTemplate::from);
        }
        var companyTemplate = templates.findActiveForCompany(
                store.getEmpresa().getId(), type, format);
        if (companyTemplate.isPresent()) {
            return companyTemplate.map(ResolvedDocumentTemplate::from);
        }
        var systemTemplate = templates.findActiveForSystem(type, format)
                .map(ResolvedDocumentTemplate::from);
        if (systemTemplate.isPresent()) {
            return systemTemplate;
        }
        if (type == DocumentTemplateType.TICKET
                && format == DocumentTemplateFormat.TICKET_80) {
            return Optional.of(ResolvedDocumentTemplate.builtInTicket());
        }
        return Optional.empty();
    }
}
