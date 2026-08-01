package com.tpverp.backend.catalog;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalEanService {

    static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final InternalEanConfigurationService configurationService;
    private final InternalEanAllocationRepository allocations;
    private final InternalEanSequenceRepository sequences;
    private final ProductIdentifierRepository identifiers;
    private final CatalogService catalog;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final SaleOperationSecurityService operationSecurity;
    private final AuditService audit;
    private final Clock clock;

    public InternalEanService(
            InternalEanConfigurationService configurationService,
            InternalEanAllocationRepository allocations,
            InternalEanSequenceRepository sequences,
            ProductIdentifierRepository identifiers,
            CatalogService catalog,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            SaleOperationSecurityService operationSecurity,
            AuditService audit,
            Clock clock) {
        this.configurationService = configurationService;
        this.allocations = allocations;
        this.sequences = sequences;
        this.identifiers = identifiers;
        this.catalog = catalog;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.operationSecurity = operationSecurity;
        this.audit = audit;
        this.clock = clock;
    }

    public InternalEanFormat.Validation validate(String code) {
        return InternalEanFormat.validate(code);
    }

    @Transactional
    public ReservationView reserve(
            InternalEanFormat format,
            OperationAuthorizationRequest credentials,
            Authentication authentication) {
        var authorization = authorize(credentials, authentication);
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var operator = organization.currentUser(authentication);
        var terminalId = currentTerminal.terminalId(authentication);
        var configuration = configurationService.requireCurrent();
        var now = clock.instant();
        var expired = allocations.findExpiredForUpdate(
                store.getId(), format, now, PageRequest.of(0, 1));
        InternalEanAllocation allocation;
        if (!expired.isEmpty()) {
            allocation = expired.getFirst();
            if (identifiers.findByStoreIdAndValor(store.getId(), allocation.getCode()).isPresent()) {
                allocations.delete(allocation);
                allocation = createFresh(
                        company.getId(), store.getId(), store.getCodigoTienda(),
                        configuration.getCompanyCode(), operator.getId(),
                        terminalId, format, now);
            } else {
                allocation.reclaim(operator.getId(), terminalId, now, RESERVATION_TTL);
            }
        } else {
            allocation = createFresh(
                    company.getId(), store.getId(), store.getCodigoTienda(),
                    configuration.getCompanyCode(), operator.getId(),
                    terminalId, format, now);
        }
        allocations.saveAndFlush(allocation);
        audit.record("INTERNAL_EAN_RESERVED", AuditResult.EXITO, auditDetails(
                allocation, authorization.authorizer().getId(), authorization.delegated(), null));
        return reservationView(allocation);
    }

    @Transactional
    public ReservationView reserveManual(
            String code,
            OperationAuthorizationRequest credentials,
            Authentication authentication) {
        var validation = requireValid(code);
        var authorization = authorize(credentials, authentication);
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var operator = organization.currentUser(authentication);
        var terminalId = currentTerminal.terminalId(authentication);
        if (identifiers.findByStoreIdAndValor(store.getId(), validation.code()).isPresent()
                || allocations.existsByCompanyIdAndCodigo(
                        company.getId(), validation.code())) {
            throw new IllegalStateException("internal_ean_code_already_used");
        }
        var now = clock.instant();
        var allocation = InternalEanAllocation.manualReservation(
                company.getId(), store.getId(), operator.getId(), terminalId,
                validation.format(), validation.code(), now, RESERVATION_TTL);
        allocations.saveAndFlush(allocation);
        audit.record("INTERNAL_EAN_RESERVED", AuditResult.EXITO, auditDetails(
                allocation, authorization.authorizer().getId(),
                authorization.delegated(), null));
        return reservationView(allocation);
    }

    @Transactional
    public ProductView assignReservationToExistingProduct(
            UUID reservationId,
            UUID productId,
            boolean replaceExisting,
            Authentication authentication) {
        var allocation = ownedReservation(reservationId, authentication);
        var product = catalog.product(productId);
        requireSecondaryReplacement(product, allocation.getCode(), replaceExisting);
        var saved = catalog.assignSecondaryBarcode(productId, allocation.getCode());
        allocation.assign(productId, IdentifierType.CODIGO_BARRAS_2, clock.instant());
        allocations.saveAndFlush(allocation);
        audit.record("INTERNAL_EAN_ASSIGNED", AuditResult.EXITO,
                auditDetails(allocation, null, false, productId));
        return ProductView.publicView(saved);
    }

    @Transactional
    public ProductView createProductFromReservation(
            UUID reservationId,
            CatalogService.ProductRequest request,
            Authentication authentication) {
        var allocation = ownedReservation(reservationId, authentication);
        var saved = catalog.createProductWithPrimaryBarcode(request, allocation.getCode());
        allocation.assign(saved.getId(), IdentifierType.CODIGO_BARRAS, clock.instant());
        allocations.saveAndFlush(allocation);
        audit.record("INTERNAL_EAN_ASSIGNED", AuditResult.EXITO,
                auditDetails(allocation, null, false, saved.getId()));
        return ProductView.publicView(saved);
    }

    @Transactional(readOnly = true)
    public UUID requireAssignedProduct(
            UUID allocationId,
            UUID productId,
            Authentication authentication) {
        var store = organization.currentStore();
        var operator = organization.currentUser(authentication);
        var terminalId = currentTerminal.terminalId(authentication);
        var allocation = allocations.findById(allocationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "internal_ean_assignment_not_found"));
        allocation.requireAssignedOwned(
                store.getId(), operator.getId(), terminalId, productId);
        return productId;
    }

    @Transactional
    public ProductView assignManualToExistingProduct(
            String code,
            UUID productId,
            boolean replaceExisting,
            OperationAuthorizationRequest credentials,
            Authentication authentication) {
        var validation = requireValid(code);
        var authorization = authorize(credentials, authentication);
        var product = catalog.product(productId);
        requireSecondaryReplacement(product, validation.code(), replaceExisting);
        var saved = catalog.assignSecondaryBarcode(productId, validation.code());
        saveManualAllocation(
                validation, saved.getId(), IdentifierType.CODIGO_BARRAS_2,
                authorization, authentication);
        return ProductView.publicView(saved);
    }

    @Transactional
    public ProductView createProductWithManualCode(
            String code,
            CatalogService.ProductRequest request,
            OperationAuthorizationRequest credentials,
            Authentication authentication) {
        var validation = requireValid(code);
        var authorization = authorize(credentials, authentication);
        var saved = catalog.createProductWithPrimaryBarcode(request, validation.code());
        saveManualAllocation(
                validation, saved.getId(), IdentifierType.CODIGO_BARRAS,
                authorization, authentication);
        return ProductView.publicView(saved);
    }

    private InternalEanAllocation createFresh(
            UUID companyId,
            UUID storeId,
            String storeCode,
            String companyCode,
            UUID operatorId,
            UUID terminalId,
            InternalEanFormat format,
            java.time.Instant now) {
        while (true) {
            var sequence = sequences.next(storeId, format);
            var code = format.compose(companyCode, storeCode, sequence);
            if (identifiers.findByStoreIdAndValor(storeId, code).isEmpty()
                    && !allocations.existsByCompanyIdAndCodigo(companyId, code)) {
                return InternalEanAllocation.reservation(
                        companyId, storeId, operatorId, terminalId, format,
                        code, now, RESERVATION_TTL);
            }
        }
    }

    private InternalEanAllocation ownedReservation(
            UUID reservationId,
            Authentication authentication) {
        var store = organization.currentStore();
        var operator = organization.currentUser(authentication);
        var terminalId = currentTerminal.terminalId(authentication);
        var allocation = allocations.findForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "internal_ean_reservation_not_found"));
        allocation.requireOwnedActive(
                store.getId(), operator.getId(), terminalId, clock.instant());
        return allocation;
    }

    private void saveManualAllocation(
            InternalEanFormat.Validation validation,
            UUID productId,
            IdentifierType identifierType,
            com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization authorization,
            Authentication authentication) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var operator = organization.currentUser(authentication);
        var terminalId = currentTerminal.terminalId(authentication);
        var now = clock.instant();
        var allocation = InternalEanAllocation.manualAssignment(
                company.getId(), store.getId(), productId, operator.getId(),
                terminalId, validation.format(), validation.code(),
                identifierType, now);
        allocations.saveAndFlush(allocation);
        audit.record("INTERNAL_EAN_ASSIGNED", AuditResult.EXITO, auditDetails(
                allocation, authorization.authorizer().getId(),
                authorization.delegated(), productId));
    }

    private com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization authorize(
            OperationAuthorizationRequest credentials,
            Authentication authentication) {
        var value = credentials == null
                ? OperationAuthorizationRequest.empty()
                : credentials;
        return operationSecurity.authorize(
                SaleOperationCode.GENERATE_PRODUCT_EAN,
                value.authorizerUsername(),
                value.authorizerPassword(),
                authentication);
    }

    private static InternalEanFormat.Validation requireValid(String code) {
        var validation = InternalEanFormat.validate(code);
        if (!validation.valid()) {
            throw new IllegalArgumentException("internal_ean_code_invalid");
        }
        return validation;
    }

    private static void requireSecondaryReplacement(
            Product product,
            String code,
            boolean replaceExisting) {
        var current = product.getBarcode2();
        if (current != null && !current.isBlank()
                && !current.equals(code) && !replaceExisting) {
            throw new IllegalStateException(
                    "internal_ean_secondary_barcode_replace_confirmation_required");
        }
    }

    private static ReservationView reservationView(
            InternalEanAllocation allocation) {
        return new ReservationView(
                allocation.getId(), allocation.getFormat(),
                allocation.getCode(), allocation.getExpiresAt());
    }

    private static LinkedHashMap<String, Object> auditDetails(
            InternalEanAllocation allocation,
            UUID authorizerId,
            boolean delegated,
            UUID productId) {
        var details = new LinkedHashMap<String, Object>();
        details.put("allocationId", allocation.getId().toString());
        details.put("code", allocation.getCode());
        details.put("format", allocation.getFormat().name());
        if (productId != null) details.put("productId", productId.toString());
        if (authorizerId != null) details.put("authorizerId", authorizerId.toString());
        details.put("delegated", delegated);
        return details;
    }

    public record ReservationView(
            UUID reservationId,
            InternalEanFormat format,
            String code,
            java.time.Instant expiresAt) {
    }
}
