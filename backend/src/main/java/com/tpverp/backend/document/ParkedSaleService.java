package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.control.ControlAlertDetectionService.ParkedSaleDeletionSnapshot;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParkedSaleService {

    private final ParkedSaleRepository sales;
    private final ParkedSaleRecoveryRepository recoveries;
    private final CurrentOrganization organization;
    private final AuditService audit;
    private final Clock clock;
    private final PosCashService posSales;
    private final SaleDocumentMutationAuthorizationService mutationAuthorizations;
    private final OperationalPermissionAuthorizationService permissionAuthorizations;
    private final ControlAlertDetectionService controlAlerts;
    private final CurrentTerminal currentTerminal;

    @org.springframework.beans.factory.annotation.Autowired
    public ParkedSaleService(
            ParkedSaleRepository sales, ParkedSaleRecoveryRepository recoveries,
            CurrentOrganization organization,
            AuditService audit,
            Clock clock,
            PosCashService posSales,
            SaleDocumentMutationAuthorizationService mutationAuthorizations,
            OperationalPermissionAuthorizationService permissionAuthorizations,
            ControlAlertDetectionService controlAlerts,
            CurrentTerminal currentTerminal) {
        this.sales = sales;
        this.recoveries = recoveries;
        this.organization = organization;
        this.audit = audit;
        this.clock = clock;
        this.posSales = posSales;
        this.mutationAuthorizations = mutationAuthorizations;
        this.permissionAuthorizations = permissionAuthorizations;
        this.controlAlerts = controlAlerts;
        this.currentTerminal = currentTerminal;
    }

    ParkedSaleService(
            ParkedSaleRepository sales, ParkedSaleRecoveryRepository recoveries,
            CurrentOrganization organization,
            AuditService audit,
            Clock clock,
            SaleDocumentMutationAuthorizationService mutationAuthorizations,
            OperationalPermissionAuthorizationService permissionAuthorizations,
            ControlAlertDetectionService controlAlerts,
            CurrentTerminal currentTerminal) {
        this(sales, recoveries, organization, audit, clock, null,
                mutationAuthorizations, permissionAuthorizations, controlAlerts, currentTerminal);
    }

    @Transactional
    public ParkedSale park(
            DocumentCommand command, String comment, Authentication authentication) {
        return park(command, comment, SalePrintMode.DEFAULT, authentication);
    }

    @Transactional
    public ParkedSale park(
            DocumentCommand command,
            String comment,
            SalePrintMode printMode,
            Authentication authentication) {
        return park(
                command, comment, printMode, Map.of(), authentication);
    }

    @Transactional
    public ParkedSale park(
            DocumentCommand command,
            String comment,
            SalePrintMode printMode,
            Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations,
            Authentication authentication) {
        mutationAuthorizations.authorize(
                command,
                operationAuthorizations,
                authentication,
                "DIRECT_PARKED_SALE",
                null);
        return persist(command, comment, printMode, authentication);
    }

    private ParkedSale persist(
            DocumentCommand command,
            String comment,
            SalePrintMode printMode,
            Authentication authentication) {
        if (command.tipo() != CommercialDocumentType.TICKET) {
            throw new IllegalArgumentException("solo se aparcan tickets");
        }
        if (command.lineas() == null || command.lineas().isEmpty()) {
            throw new IllegalArgumentException("la venta aparcada necesita lineas");
        }
        var store = organization.currentStore();
        var user = organization.currentUser(authentication);
        return sales.save(new ParkedSale(
                store.getId(), user.getId(), Instant.now(clock), command, comment, printMode));
    }

    @Transactional
    public ParkedSale parkFromPos(
            PosCashController.SaleRequest sale,
            String comment,
            SalePrintMode printMode,
            Authentication authentication) {
        if (posSales == null) {
            throw new IllegalStateException(
                    "pos_sale_authorization_service_unavailable");
        }
        var authorizationSourceId = UUID.randomUUID();
        var command = posSales.authorizeCommandForMutation(
                sale,
                authentication,
                "PARKED_SALE",
                authorizationSourceId);
        return persist(command, comment, printMode, authentication);
    }

    // Guarda una venta sin numeracion fiscal ni pagos para recuperarla despues.

    @Transactional(readOnly = true)
    public List<ParkedSale> list() {
        return sales.findAllByTiendaIdOrderByCreadoEnDesc(
                organization.currentStore().getId());
    }

    @Transactional
    public ParkedSaleOpened open(UUID id) {
        var sale = find(id);
        return new ParkedSaleOpened(
                sale.documentCommand(), sale.getComment(), sale.getPrintMode());
    }

    @Transactional
    public ParkedSaleRecoveryView recover(
            UUID saleId, UUID recoveryId, Authentication authentication) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var replay = recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId());
        if (replay.isPresent()) {
            return recoveryView(requireIdentity(
                    replay.orElseThrow(), saleId, store.getId(), company.getId()));
        }

        var sale = sales.findLockedByIdAndStoreId(saleId, store.getId())
                .orElseThrow(() -> new IllegalArgumentException("venta aparcada no encontrada"));
        replay = recoveries.findByRecoveryIdAndStoreIdAndCompanyId(
                recoveryId, store.getId(), company.getId());
        if (replay.isPresent()) {
            return recoveryView(requireIdentity(
                    replay.orElseThrow(), saleId, store.getId(), company.getId()));
        }
        var active = recoveries.findByParkedSaleIdAndStoreIdAndCompanyId(
                saleId, store.getId(), company.getId());
        if (active.isPresent()) {
            throw new IllegalStateException("parked_sale_recovery_already_claimed");
        }
        var recovery = new ParkedSaleRecovery(
                recoveryId, sale, company.getId(),
                organization.currentUser(authentication).getId(), Instant.now(clock));
        return recoveryView(recoveries.save(recovery));
    }

    @Transactional
    public ParkedSaleRecoveryView acknowledge(
            UUID saleId, UUID recoveryId, Authentication authentication) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var operator = organization.currentUser(authentication);
        var recovery = recoveries.findLocked(
                        recoveryId, store.getId(), company.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "parked_sale_recovery_not_found"));
        requireIdentity(recovery, saleId, store.getId(), company.getId());
        if (!recovery.getUserId().equals(operator.getId())) {
            throw new IllegalStateException(
                    "parked_sale_recovery_owned_by_another_user");
        }
        if (recovery.getStatus() == ParkedSaleRecovery.Status.CLAIMED) {
            sales.findLockedByIdAndStoreId(saleId, store.getId())
                    .ifPresent(sales::delete);
            recovery.acknowledge(Instant.now(clock));
            recoveries.save(recovery);
            var details = new LinkedHashMap<String, Object>();
            details.put("parkedSaleId", saleId.toString());
            details.put("recoveryId", recoveryId.toString());
            details.put("operatorId", operator.getId().toString());
            details.put("operatorUsername", operator.getUserName());
            audit.record(
                    "PARKED_SALE_RECOVERED",
                    AuditResult.EXITO,
                    java.util.Map.copyOf(details));
        }
        return recoveryView(recovery);
    }

    @Transactional
    public void delete(UUID id, Authentication authentication) {
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var sale = sales.findLockedByIdAndStoreId(id, store.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "venta aparcada no encontrada"));
        if (recoveries.findByParkedSaleIdAndStoreIdAndCompanyId(
                id, store.getId(), company.getId()).isPresent()) {
            throw new IllegalStateException("parked_sale_recovery_already_claimed");
        }
        var operator = organization.currentUser(authentication);
        var snapshot = deletionSnapshot(sale);
        sales.delete(sale);
        sales.flush();
        var details = new LinkedHashMap<String, Object>();
        details.put("parkedSaleId", sale.getId().toString());
        details.put("operatorId", operator.getId().toString());
        details.put("operatorUsername", operator.getUserName());
        details.put("bulk", false);
        audit.record(
                "PARKED_SALE_DELETED",
                AuditResult.EXITO,
                java.util.Map.copyOf(details));
        controlAlerts.detectParkedSalesDeleted(
                sale.getId(), List.of(snapshot), false,
                currentTerminal.terminalId(authentication),
                operator.getId(), operator.getUserName(), false, authentication);
    }

    @Transactional
    public int deleteAll(
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var authorization = permissionAuthorizations.authorizeWithPassword(
                Set.of(CorePermissionBootstrap.GESTION_VENTAS),
                authorizerUsername,
                authorizerPassword,
                authentication);
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var parkedSales = sales.findAllLockedByStoreId(store.getId());
        if (parkedSales.isEmpty()) {
            throw new IllegalStateException("parked_sales_empty");
        }
        var ids = parkedSales.stream().map(ParkedSale::getId).toList();
        if (recoveries.existsByParkedSaleIdInAndStoreIdAndCompanyId(
                ids, store.getId(), company.getId())) {
            throw new IllegalStateException("parked_sale_recovery_already_claimed");
        }
        var snapshots = parkedSales.stream().map(ParkedSaleService::deletionSnapshot).toList();
        var operationId = UUID.randomUUID();
        sales.deleteAll(parkedSales);
        sales.flush();
        var details = new LinkedHashMap<String, Object>();
        details.put("operation", SaleOperationCode.DELETE_PARKED_SALE.name());
        details.put("operationId", operationId.toString());
        details.put("parkedSaleIds", ids.stream().map(UUID::toString).toList());
        details.put("deletedCount", parkedSales.size());
        details.put("operatorId", authorization.operator().getId().toString());
        details.put("operatorUsername", authorization.operator().getUserName());
        details.put("authorizerId", authorization.authorizer().getId().toString());
        details.put("authorizerUsername", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
        details.put("bulk", true);
        audit.record("PARKED_SALES_DELETED", AuditResult.EXITO, Map.copyOf(details));
        controlAlerts.detectParkedSalesDeleted(
                operationId, snapshots, true,
                currentTerminal.terminalId(authentication),
                authorization.authorizer().getId(),
                authorization.authorizer().getUserName(),
                authorization.delegated(), authentication);
        return parkedSales.size();
    }
    // El borrador solo se elimina al confirmar la reconstruccion local. La recuperacion
    // conserva una instantanea y una clave idempotente para que un reintento no duplique
    // ni pierda la venta.

    private ParkedSale find(UUID id) {
        return sales.findByIdAndTiendaId(id, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "venta aparcada no encontrada"));
    }

    private static ParkedSaleDeletionSnapshot deletionSnapshot(ParkedSale sale) {
        return new ParkedSaleDeletionSnapshot(
                sale.getId(), sale.getCreatedAt(), sale.getComment(), sale.getTotal());
    }

    private static ParkedSaleRecovery requireIdentity(
            ParkedSaleRecovery recovery, UUID saleId, UUID storeId, UUID companyId) {
        if (!recovery.matches(saleId, storeId, companyId)) {
            throw new IllegalStateException("parked_sale_recovery_idempotency_conflict");
        }
        return recovery;
    }

    private static ParkedSaleRecoveryView recoveryView(ParkedSaleRecovery recovery) {
        return new ParkedSaleRecoveryView(
                recovery.getRecoveryId(), recovery.getParkedSaleId(),
                recovery.getStatus(), recovery.opened());
    }

    public record ParkedSaleRecoveryView(
            UUID recoveryId, UUID parkedSaleId, ParkedSaleRecovery.Status status,
            ParkedSaleOpened sale) {}
}
