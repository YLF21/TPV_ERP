package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleDocumentAuthorizationManifestService {

    private final SaleDocumentAuthorizationManifestRepository manifests;
    private final SaleDocumentAuthorizationFingerprint fingerprints;
    private final SaleOperationSecurityService operationSecurity;
    private final CurrentOrganization organization;
    private final Clock clock;

    public SaleDocumentAuthorizationManifestService(
            SaleDocumentAuthorizationManifestRepository manifests,
            SaleDocumentAuthorizationFingerprint fingerprints,
            SaleOperationSecurityService operationSecurity,
            CurrentOrganization organization,
            Clock clock) {
        this.manifests = manifests;
        this.fingerprints = fingerprints;
        this.operationSecurity = operationSecurity;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            CommercialDocument document,
            SaleDocumentMutationAuthorizationService.AuthorizationProof proof) {
        requireDraftInCurrentStore(document);
        Objects.requireNonNull(proof, "proof");
        if (manifests.existsById(document.getId())) {
            throw new IllegalStateException(
                    "sale_document_authorization_manifest_already_exists");
        }
        manifests.saveAndFlush(new SaleDocumentAuthorizationManifest(
                document.getId(),
                document.getTiendaId(),
                fingerprints.fingerprint(document),
                proof.policyVersions(),
                clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Validation validate(CommercialDocument document) {
        requireDraftInCurrentStore(document);
        var manifest = manifests.findForUpdate(
                        document.getId(), document.getTiendaId())
                .orElseThrow(GenericSaleConfirmationBlockedException::new);
        if (!manifest.matches(fingerprints.fingerprint(document))) {
            throw GenericSaleConfirmationBlockedException.mismatch();
        }
        var storedVersions = manifest.getPolicyVersions();
        var policyChanged = storedVersions.entrySet().stream().anyMatch(entry ->
                operationSecurity.resolve(entry.getKey()).version()
                        != entry.getValue());
        return new Validation(storedVersions.keySet(), policyChanged);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(
            CommercialDocument document,
            SaleDocumentMutationAuthorizationService.AuthorizationProof proof) {
        requireDraftInCurrentStore(document);
        Objects.requireNonNull(proof, "proof");
        var manifest = manifests.findForUpdate(
                        document.getId(), document.getTiendaId())
                .orElseThrow(GenericSaleConfirmationBlockedException::new);
        manifest.refreshPolicies(
                fingerprints.fingerprint(document),
                proof.policyVersions(),
                clock.instant());
        manifests.saveAndFlush(manifest);
    }

    private void requireDraftInCurrentStore(CommercialDocument document) {
        Objects.requireNonNull(document, "document");
        var currentStoreId = organization.currentStore().getId();
        if (!currentStoreId.equals(document.getTiendaId())) {
            throw new IllegalArgumentException("Documento no encontrado");
        }
        if (document.getEstado() != DocumentStatus.BORRADOR) {
            throw new IllegalStateException(
                    "sale_document_authorization_manifest_requires_draft");
        }
    }

    public record Validation(
            Set<SaleOperationCode> operations,
            boolean policyChanged) {

        public Validation {
            operations = Set.copyOf(operations);
        }
    }
}
