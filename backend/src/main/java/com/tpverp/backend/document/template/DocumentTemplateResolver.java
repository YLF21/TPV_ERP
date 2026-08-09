package com.tpverp.backend.document.template;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
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

    @Transactional(readOnly = true)
    public ResolvedDocumentTemplate resolve(DocumentTemplateType type) {
        return resolve(organization.currentStore(), type);
    }

    @Transactional(readOnly = true)
    public ResolvedDocumentTemplate resolve(Store store, DocumentTemplateType type) {
        var storeTemplate = templates.findActiveForStore(store.getId(), type);
        if (storeTemplate.isPresent()) {
            return ResolvedDocumentTemplate.from(storeTemplate.get());
        }
        var companyTemplate = templates.findActiveForCompany(store.getEmpresa().getId(), type);
        if (companyTemplate.isPresent()) {
            return ResolvedDocumentTemplate.from(companyTemplate.get());
        }
        return templates.findActiveForSystem(type)
                .map(ResolvedDocumentTemplate::from)
                .orElseGet(() -> ResolvedDocumentTemplate.builtIn(type));
    }
}
