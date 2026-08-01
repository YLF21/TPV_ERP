package com.tpverp.backend.catalog;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.control.ControlAlertDetectionService;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductEditAuthorizationService {

    public static final String HEADER = "X-Operational-Authorization";
    private static final Duration VALIDITY = Duration.ofMinutes(15);

    private final CatalogService catalog;
    private final CurrentOrganization organization;
    private final SaleOperationSecurityService operationSecurity;
    private final ControlAlertDetectionService controlAlerts;
    private final AuditService audit;
    private final Clock clock;
    private final Map<UUID, Grant> grants = new ConcurrentHashMap<>();

    public ProductEditAuthorizationService(
            CatalogService catalog,
            CurrentOrganization organization,
            SaleOperationSecurityService operationSecurity,
            ControlAlertDetectionService controlAlerts,
            AuditService audit,
            Clock clock) {
        this.catalog = catalog;
        this.organization = organization;
        this.operationSecurity = operationSecurity;
        this.controlAlerts = controlAlerts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuthorizationView authorize(
            UUID productId,
            String authorizerUsername,
            String authorizerPassword,
            Authentication authentication) {
        var product = catalog.product(productId);
        var authorization = operationSecurity.authorize(
                SaleOperationCode.EDIT_CATALOG_PRODUCT,
                authorizerUsername,
                authorizerPassword,
                authentication);
        var operationId = UUID.randomUUID();
        var expiresAt = clock.instant().plus(VALIDITY);
        var grant = new Grant(
                operationId,
                organization.currentStore().getId(),
                product.getId(),
                authorization.operator().getId(),
                authorization.operator().getUserName(),
                authorization.authorizer().getId(),
                authorization.authorizer().getUserName(),
                authorization.delegated(),
                expiresAt);
        grants.put(operationId, grant);
        purgeExpired(clock.instant());
        audit.record("PRODUCT_EDIT_AUTHORIZED", AuditResult.EXITO, details(grant, product, "AUTHORIZE"));
        return new AuthorizationView(
                operationId,
                authorization.authorizer().getUserName(),
                authorization.delegated(),
                expiresAt,
                ProductView.managementView(product));
    }

    @Transactional(readOnly = true)
    public Optional<Grant> validGrant(
            UUID operationId,
            UUID productId,
            Authentication authentication) {
        var now = clock.instant();
        var grant = grants.get(operationId);
        if (grant == null || grant.expiresAt().isBefore(now)) {
            grants.remove(operationId);
            return Optional.empty();
        }
        var operator = organization.currentUser(authentication);
        if (!grant.productId().equals(productId)
                || !grant.operatorId().equals(operator.getId())
                || !grant.storeId().equals(organization.currentStore().getId())) {
            return Optional.empty();
        }
        return Optional.of(grant);
    }

    @Transactional
    public void recordMutation(Grant grant, String mutation, Authentication authentication) {
        var product = catalog.product(grant.productId());
        var details = details(grant, product, mutation);
        audit.record("PRODUCT_CATALOG_MODIFIED", AuditResult.EXITO, details);
        controlAlerts.detectProductCatalogModified(
                grant.operationId(),
                product.getId(),
                product.getCode(),
                product.getName(),
                mutation,
                terminalId(authentication),
                grant.authorizerId(),
                grant.authorizerName(),
                grant.delegated(),
                authentication);
    }

    public void revoke(UUID operationId, Authentication authentication) {
        var grant = grants.get(operationId);
        if (grant == null) return;
        var operator = organization.currentUser(authentication);
        if (grant.operatorId().equals(operator.getId())
                && grant.storeId().equals(organization.currentStore().getId())) {
            grants.remove(operationId);
        }
    }

    private Map<String, Object> details(Grant grant, Product product, String mutation) {
        var values = new LinkedHashMap<String, Object>();
        values.put("operationId", grant.operationId().toString());
        values.put("productId", product.getId().toString());
        values.put("productCode", product.getCode() == null ? "" : product.getCode());
        values.put("productName", product.getName());
        values.put("mutation", mutation);
        values.put("operatorId", grant.operatorId().toString());
        values.put("operatorName", grant.operatorName());
        values.put("authorizerId", grant.authorizerId().toString());
        values.put("authorizerName", grant.authorizerName());
        values.put("delegated", grant.delegated());
        return values;
    }

    private void purgeExpired(Instant now) {
        grants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static UUID terminalId(Authentication authentication) {
        return authentication.getDetails() instanceof OperationalSessionContext context
                ? context.terminalId()
                : null;
    }

    public record Grant(
            UUID operationId,
            UUID storeId,
            UUID productId,
            UUID operatorId,
            String operatorName,
            UUID authorizerId,
            String authorizerName,
            boolean delegated,
            Instant expiresAt) {
    }

    public record AuthorizationView(
            UUID operationId,
            String authorizedBy,
            boolean delegated,
            Instant expiresAt,
            ProductView product) {
    }
}
