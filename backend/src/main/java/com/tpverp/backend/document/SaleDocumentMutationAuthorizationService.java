package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Authoritative guard for client-built sales documents.
 *
 * <p>Override flags are explicit intent, while the catalogue and the
 * authoritative pricing pipeline remain the source of truth. A flag can enable
 * an override only when its value is coherent with the current catalogue. When
 * a flag is absent, the document service replaces client name/price values with
 * the current catalogue, member or promotion price.</p>
 */
@Service
public class SaleDocumentMutationAuthorizationService {

    static final String SALE_OPERATION_AUTHORIZED = "SALE_OPERATION_AUTHORIZED";

    private final ProductRepository products;
    private final PaymentMethodRepository paymentMethods;
    private final CurrentOrganization organization;
    private final SaleOperationSecurityService operationSecurity;
    private final DiscountAuthorizationService discountAuthorizations;
    private final AuditService audit;
    private TemporaryPriceAuthorizationService temporaryPriceAuthorizations;

    public SaleDocumentMutationAuthorizationService(
            ProductRepository products,
            PaymentMethodRepository paymentMethods,
            CurrentOrganization organization,
            SaleOperationSecurityService operationSecurity,
            DiscountAuthorizationService discountAuthorizations,
            AuditService audit) {
        this.products = products;
        this.paymentMethods = paymentMethods;
        this.organization = organization;
        this.operationSecurity = operationSecurity;
        this.discountAuthorizations = discountAuthorizations;
        this.audit = audit;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTemporaryPriceAuthorizationService(
            TemporaryPriceAuthorizationService temporaryPriceAuthorizations) {
        this.temporaryPriceAuthorizations = temporaryPriceAuthorizations;
    }

    public AuthorizationProof authorize(
            DocumentCommand command,
            Map<SaleOperationCode, OperationAuthorizationRequest> requestedAuthorizations,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        return authorize(command, List.of(), requestedAuthorizations,
                authentication, sourceType, sourceId);
    }

    public AuthorizationProof authorize(
            DocumentCommand command,
            List<DocumentRequest.LineRequest> requestedLines,
            Map<SaleOperationCode, OperationAuthorizationRequest> requestedAuthorizations,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        Objects.requireNonNull(command, "command");
        var operations = EnumSet.noneOf(SaleOperationCode.class);
        var discountPercentages = new EnumMap<SaleOperationCode, BigDecimal>(
                SaleOperationCode.class);
        var maximumDiscount = command.descuentoGlobal() == null
                ? BigDecimal.ZERO
                : command.descuentoGlobal();
        var storeId = organization.currentStore().getId();

        for (var line : command.lineas()) {
            if (line.lineType() != null
                    && line.lineType() != DocumentLineType.PRODUCT) {
                continue;
            }
            if (line.cantidad().signum() < 0
                    && line.cantidad().compareTo(BigDecimal.ONE.negate()) != 0) {
                throw new IllegalArgumentException(
                        "manual_return_quantity_must_be_minus_one");
            }
            var product = products.findById(line.productoId())
                    .filter(value -> value.getStoreId().equals(storeId))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Producto no encontrado"));

            if (line.cantidad().signum() < 0) {
                operations.add(SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET);
            }
            if (line.descuento() != null && line.descuento().signum() > 0) {
                operations.add(SaleOperationCode.APPLY_SALE_DISCOUNT);
                maximumDiscount = maximumDiscount.max(line.descuento());
            }

            var catalogName = product.getName() == null ? "" : product.getName().trim();
            if (line.temporaryNameOverride()) {
                var requestedName = line.nombre() == null ? "" : line.nombre().trim();
                if (requestedName.isEmpty() || requestedName.equals(catalogName)) {
                    throw new IllegalArgumentException(
                            "temporary_name_override_is_inconsistent");
                }
                operations.add(SaleOperationCode.TEMPORARY_NAME);
            }

            var catalogPrice = Money.euros(product.getSalePrice());
            var requestedPrice = Money.euros(line.precioUnitario());
            if (catalogPrice.signum() == 0) {
                if (line.temporaryPriceOverride()) {
                    throw new IllegalArgumentException(
                            "temporary_price_override_not_allowed_for_open_price_product");
                }
                if (requestedPrice.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "open_unit_price_must_be_greater_than_zero");
                }
                operations.add(SaleOperationCode.OPEN_PRICE_PRODUCT);
            } else if (line.temporaryPriceOverride()) {
                if (requestedPrice.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "temporary_price_must_be_greater_than_zero");
                }
                operations.add(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
            }
        }

        if (maximumDiscount.signum() > 0) {
            operations.add(SaleOperationCode.APPLY_SALE_DISCOUNT);
            discountPercentages.put(
                    SaleOperationCode.APPLY_SALE_DISCOUNT, maximumDiscount);
        }

        Long temporaryPricePolicyVersion = null;
        if (operations.contains(SaleOperationCode.TEMPORARY_PRICE_CHANGE)
                && temporaryPriceAuthorizations != null
                && requestedLines != null
                && !requestedLines.isEmpty()) {
            if (requestedLines.size() != command.lineas().size()) {
                throw new IllegalArgumentException(
                        "temporary_price_authorization_lines_mismatch");
            }
            var claims = requestedLines.stream()
                    .filter(DocumentRequest.LineRequest::temporaryPriceOverride)
                    .map(line -> new TemporaryPriceAuthorizationService.ClaimRequest(
                            line.cartLineId(), line.productoId(), line.precioUnitario(),
                            line.temporaryPriceAuthorizationToken()))
                    .toList();
            temporaryPriceAuthorizations.claimAll(
                    claims, authentication, sourceType, sourceId);
            temporaryPricePolicyVersion = operationSecurity
                    .resolve(SaleOperationCode.TEMPORARY_PRICE_CHANGE).version();
            operations.remove(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
        }

        var proof = authorizeOperations(
                operations,
                discountPercentages,
                requestedAuthorizations,
                authentication,
                sourceType,
                sourceId);
        if (temporaryPricePolicyVersion == null) return proof;
        temporaryPriceAuthorizations.consume(sourceType, sourceId);
        var versions = new EnumMap<SaleOperationCode, Long>(SaleOperationCode.class);
        versions.putAll(proof.policyVersions());
        versions.put(SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                temporaryPricePolicyVersion);
        return new AuthorizationProof(versions);
    }

    public AuthorizationProof reauthorize(
            CommercialDocument document,
            java.util.Set<SaleOperationCode> operations,
            Map<SaleOperationCode, OperationAuthorizationRequest> requestedAuthorizations,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        Objects.requireNonNull(document, "document");
        var requestedOperations = operations == null || operations.isEmpty()
                ? EnumSet.noneOf(SaleOperationCode.class)
                : EnumSet.copyOf(operations);
        if (!MUTATION_OPERATIONS.containsAll(requestedOperations)) {
            throw new IllegalStateException(
                    "sale_document_authorization_manifest_operation_invalid");
        }
        var discountPercentages = new EnumMap<SaleOperationCode, BigDecimal>(
                SaleOperationCode.class);
        if (requestedOperations.contains(SaleOperationCode.APPLY_SALE_DISCOUNT)) {
            var maximumDiscount = document.getLineas().stream()
                    .map(DocumentLine::getDescuento)
                    .reduce(document.getDescuentoGlobal(), BigDecimal::max);
            discountPercentages.put(
                    SaleOperationCode.APPLY_SALE_DISCOUNT, maximumDiscount);
        }
        return authorizeOperations(
                requestedOperations,
                discountPercentages,
                requestedAuthorizations,
                authentication,
                sourceType,
                sourceId);
    }

    private AuthorizationProof authorizeOperations(
            java.util.Set<SaleOperationCode> operations,
            Map<SaleOperationCode, BigDecimal> discountPercentages,
            Map<SaleOperationCode, OperationAuthorizationRequest> requestedAuthorizations,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        var policyVersions = new EnumMap<SaleOperationCode, Long>(
                SaleOperationCode.class);
        var credentials = requestedAuthorizations == null
                ? Map.<SaleOperationCode, OperationAuthorizationRequest>of()
                : requestedAuthorizations;
        for (var operationCode : operations) {
            var policy = operationSecurity.resolve(operationCode);
            var requested = credentials.getOrDefault(
                    operationCode, OperationAuthorizationRequest.empty());
            var requestedDiscount = discountPercentages.get(operationCode);
            var authorization = authorizeOperation(
                    operationCode,
                    policy,
                    requested,
                    requestedDiscount,
                    authentication);
            if (requestedDiscount != null && policy.requirePermission()) {
                discountAuthorizations.enforceAuthorizerLimit(
                        requestedDiscount, authorization.authorizer());
            }
            auditAuthorization(
                    operationCode,
                    policy,
                    authorization,
                    sourceType,
                    sourceId);
            policyVersions.put(operationCode, policy.version());
        }
        return new AuthorizationProof(policyVersions);
    }

    /**
     * Authorizes payment methods whose confirmation is configurable in APP
     * VENTA. Integrated card metadata is accepted only by a flow that carries
     * a persisted acquiring-operation id and validates it before mutation.
     */
    public void authorizePayments(
            PaymentRequest request,
            Map<SaleOperationCode, OperationAuthorizationRequest> requestedAuthorizations,
            IntegratedPaymentPolicy integratedPaymentPolicy,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        Objects.requireNonNull(request, "request");
        authorizePayments(
                request.pagos(),
                requestedAuthorizations,
                integratedPaymentPolicy,
                authentication,
                sourceType,
                sourceId);
    }

    public void authorizePayments(
            java.util.List<PaymentRequest.Item> requestedPayments,
            Map<SaleOperationCode, OperationAuthorizationRequest> requestedAuthorizations,
            IntegratedPaymentPolicy integratedPaymentPolicy,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        Objects.requireNonNull(integratedPaymentPolicy, "integratedPaymentPolicy");
        var operations = EnumSet.noneOf(SaleOperationCode.class);
        var companyId = organization.currentCompany().getId();
        for (var payment : Objects.requireNonNull(requestedPayments, "requestedPayments")) {
            Objects.requireNonNull(payment, "payment");
            if (payment.paymentTerminalOperationId() != null) {
                // A direct caller cannot turn a wallet into a terminal payment by
                // supplying an operation id. Keep the historical operation path
                // for known non-wallet methods and for legacy unknown metadata.
                paymentMethods.findByIdAndEmpresaId(payment.metodoPagoId(), companyId)
                        .ifPresent(DirectDocumentPaymentGuard::requireAllowed);
                if (integratedPaymentPolicy == IntegratedPaymentPolicy.REJECT_UNPROVEN
                        || payment.cardMode()
                        == com.tpverp.backend.terminal.PaymentCardMode.MANUAL) {
                    throw new IllegalArgumentException(
                            "legacy_integrated_card_payment_not_supported");
                }
                continue;
            }
            if (payment.cardMode()
                    == com.tpverp.backend.terminal.PaymentCardMode.INTEGRATED) {
                throw new IllegalArgumentException(
                        "legacy_integrated_card_payment_not_supported");
            }
            var method = paymentMethods.findByIdAndEmpresaId(
                            payment.metodoPagoId(), companyId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Metodo de pago no encontrado"));
            DirectDocumentPaymentGuard.requireAllowed(method);
            if ("TARJETA".equals(method.getNombre())) {
                operations.add(SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT);
            } else if ("TRANSFERENCIA".equals(method.getNombre())) {
                operations.add(SaleOperationCode.CONFIRM_TRANSFER_PAYMENT);
            }
        }

        var credentials = requestedAuthorizations == null
                ? Map.<SaleOperationCode, OperationAuthorizationRequest>of()
                : requestedAuthorizations;
        for (var operationCode : operations) {
            var policy = operationSecurity.resolve(operationCode);
            var requested = credentials.getOrDefault(
                    operationCode, OperationAuthorizationRequest.empty());
            var authorization = authorizeOperation(
                    operationCode,
                    policy,
                    requested,
                    null,
                    authentication);
            auditAuthorization(
                    operationCode,
                    policy,
                    authorization,
                    sourceType,
                    sourceId);
        }
    }

    public enum IntegratedPaymentPolicy {
        REJECT_UNPROVEN,
        REQUIRE_PERSISTED_OPERATION
    }

    public record AuthorizationProof(
            Map<SaleOperationCode, Long> policyVersions) {

        public AuthorizationProof {
            var copy = new EnumMap<SaleOperationCode, Long>(
                    SaleOperationCode.class);
            if (policyVersions != null) {
                policyVersions.forEach((code, version) -> {
                    Objects.requireNonNull(code, "operationCode");
                    if (version == null || version < 0) {
                        throw new IllegalArgumentException(
                                "policyVersion must be non-negative");
                    }
                    copy.put(code, version);
                });
            }
            policyVersions = Map.copyOf(copy);
        }

        public static AuthorizationProof empty() {
            return new AuthorizationProof(Map.of());
        }
    }

    private static final EnumSet<SaleOperationCode> MUTATION_OPERATIONS =
            EnumSet.of(
                    SaleOperationCode.MANUAL_RETURN_WITHOUT_TICKET,
                    SaleOperationCode.TEMPORARY_NAME,
                    SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                    SaleOperationCode.OPEN_PRICE_PRODUCT,
                    SaleOperationCode.APPLY_SALE_DISCOUNT);

    private Authorization authorizeOperation(
            SaleOperationCode operationCode,
            SaleOperationSecurityService.ResolvedOperation policy,
            OperationAuthorizationRequest requested,
            BigDecimal requestedDiscount,
            Authentication authentication) {
        if (requestedDiscount != null && policy.requirePermission()) {
            var operator = organization.currentUser(authentication);
            var operatorHasPermission = PermissionChecks.hasRole(authentication, "ADMIN")
                    || policy.permissions().stream().anyMatch(permission ->
                    PermissionChecks.hasAuthority(authentication, permission));
            var operatorCoversDiscount = operator.getMaxDiscountPercent()
                    .compareTo(requestedDiscount) >= 0;
            if (!operatorHasPermission || !operatorCoversDiscount) {
                return operationSecurity.authorizeNamed(
                        operationCode,
                        requested.authorizerUsername(),
                        requested.authorizerPassword(),
                        authentication);
            }
        }
        return operationSecurity.authorize(
                operationCode,
                requested.authorizerUsername(),
                requested.authorizerPassword(),
                authentication);
    }

    private void auditAuthorization(
            SaleOperationCode operationCode,
            SaleOperationSecurityService.ResolvedOperation policy,
            Authorization authorization,
            String sourceType,
            UUID sourceId) {
        if (operationCode == SaleOperationCode.OPEN_PRICE_PRODUCT
                && !policy.requirePermission()
                && !policy.requirePassword()) {
            return;
        }
        var details = new LinkedHashMap<String, Object>();
        details.put("operationCode", operationCode.name());
        details.put("policyVersion", policy.version());
        details.put("requirePermission", policy.requirePermission());
        details.put("requirePassword", policy.requirePassword());
        details.put("operatorId", authorization.operator().getId().toString());
        details.put("operatorUsername", authorization.operator().getUserName());
        details.put("authorizerId", authorization.authorizer().getId().toString());
        details.put("authorizerUsername", authorization.authorizer().getUserName());
        details.put("delegated", authorization.delegated());
        if (sourceType != null && !sourceType.isBlank()) {
            details.put("sourceType", sourceType);
        }
        if (sourceId != null) {
            details.put("sourceId", sourceId.toString());
        }
        audit.record(SALE_OPERATION_AUTHORIZED, AuditResult.EXITO, Map.copyOf(details));
    }
}
