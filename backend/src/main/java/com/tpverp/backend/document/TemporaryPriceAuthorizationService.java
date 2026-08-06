package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.CurrentTerminal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemporaryPriceAuthorizationService {

    public static final Duration VALIDITY = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TemporaryPriceAuthorizationGrantRepository grants;
    private final ProductRepository products;
    private final CurrentOrganization organization;
    private final CurrentTerminal currentTerminal;
    private final SaleOperationSecurityService operationSecurity;
    private final AuditService audit;
    private final Clock clock;

    public TemporaryPriceAuthorizationService(
            TemporaryPriceAuthorizationGrantRepository grants,
            ProductRepository products,
            CurrentOrganization organization,
            CurrentTerminal currentTerminal,
            SaleOperationSecurityService operationSecurity,
            AuditService audit,
            Clock clock) {
        this.grants = grants;
        this.products = products;
        this.organization = organization;
        this.currentTerminal = currentTerminal;
        this.operationSecurity = operationSecurity;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuthorizationView authorize(
            UUID productId,
            String cartLineId,
            BigDecimal unitPrice,
            OperationAuthorizationRequest requested,
            Authentication authentication) {
        var company = organization.currentCompany();
        var store = organization.currentStore();
        var product = products.findById(productId)
                .filter(value -> value.getStoreId().equals(store.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
        if (Money.euros(product.getSalePrice()).signum() == 0) {
            throw new IllegalArgumentException(
                    "temporary_price_authorization_not_required_for_open_price_product");
        }
        var normalizedLineId = cartLineId(cartLineId);
        var normalizedPrice = positivePrice(unitPrice);
        var policy = operationSecurity.resolve(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
        var credentials = requested == null ? OperationAuthorizationRequest.empty() : requested;
        var authorization = operationSecurity.authorize(
                SaleOperationCode.TEMPORARY_PRICE_CHANGE,
                credentials.authorizerUsername(),
                credentials.authorizerPassword(),
                authentication);
        var rawToken = token();
        var now = clock.instant();
        var expiresAt = now.plus(VALIDITY);
        var grant = grants.save(new TemporaryPriceAuthorizationGrant(
                hash(rawToken), company.getId(), store.getId(),
                currentTerminal.terminalId(authentication),
                authorization.operator().getId(), authorization.operator().getUserName(),
                authorization.authorizer().getId(), authorization.authorizer().getUserName(),
                authorization.delegated(), normalizedLineId, product.getId(),
                normalizedPrice, policy.version(), now, expiresAt));
        audit.record("TEMPORARY_PRICE_CHANGE_PREAUTHORIZED", AuditResult.EXITO,
                auditDetails(grant, "ISSUED", null, null));
        return new AuthorizationView(
                rawToken, expiresAt, authorization.authorizer().getUserName(),
                authorization.delegated(), policy.version());
    }

    @Transactional
    public void claimAll(
            List<ClaimRequest> requestedClaims,
            Authentication authentication,
            String sourceType,
            UUID sourceId) {
        var claims = requestedClaims == null ? List.<ClaimRequest>of() : requestedClaims;
        if (claims.isEmpty()) return;
        var normalizedSourceType = sourceType(sourceType);
        Objects.requireNonNull(sourceId, "sourceId");
        var companyId = organization.currentCompany().getId();
        var storeId = organization.currentStore().getId();
        var terminalId = currentTerminal.terminalId(authentication);
        var operatorId = organization.currentUser(authentication).getId();
        var policy = operationSecurity.resolve(SaleOperationCode.TEMPORARY_PRICE_CHANGE);
        var lineIds = new HashSet<String>();
        var tokenHashes = new HashSet<String>();
        var now = clock.instant();
        for (var claim : claims) {
            Objects.requireNonNull(claim, "claim");
            var lineId = cartLineId(claim.cartLineId());
            var tokenHash = hash(required(claim.token(), "token"));
            if (!lineIds.add(lineId) || !tokenHashes.add(tokenHash)) {
                throw new IllegalArgumentException(
                        "temporary_price_authorization_duplicate");
            }
            var grant = grants.findForUpdateByTokenHash(tokenHash)
                    .orElseThrow(() -> new IllegalStateException(
                            "temporary_price_authorization_invalid"));
            grant.claim(companyId, storeId, terminalId, operatorId, lineId,
                    claim.productId(), positivePrice(claim.unitPrice()), policy.version(),
                    normalizedSourceType, sourceId, now);
            audit.record(PosCashService.SALE_OPERATION_AUTHORIZED, AuditResult.EXITO,
                    auditDetails(grant, "CLAIMED", normalizedSourceType, sourceId));
        }
    }

    @Transactional
    public void consume(String sourceType, UUID sourceId) {
        var normalizedSourceType = sourceType(sourceType);
        Objects.requireNonNull(sourceId, "sourceId");
        var now = clock.instant();
        grants.findClaimedForUpdate(normalizedSourceType, sourceId)
                .forEach(grant -> grant.consume(normalizedSourceType, sourceId, now));
    }

    @Transactional
    public void release(String sourceType, UUID sourceId) {
        var normalizedSourceType = sourceType(sourceType);
        Objects.requireNonNull(sourceId, "sourceId");
        grants.findClaimedForUpdate(normalizedSourceType, sourceId)
                .forEach(grant -> grant.release(normalizedSourceType, sourceId));
    }

    private static Map<String, Object> auditDetails(
            TemporaryPriceAuthorizationGrant grant,
            String phase,
            String sourceType,
            UUID sourceId) {
        var values = new LinkedHashMap<String, Object>();
        values.put("operationCode", SaleOperationCode.TEMPORARY_PRICE_CHANGE.name());
        values.put("authorizationGrantId", grant.getId().toString());
        values.put("policyVersion", grant.getPolicyVersion());
        values.put("phase", phase);
        values.put("cartLineId", grant.getCartLineId());
        values.put("productId", grant.getProductId().toString());
        values.put("unitPrice", grant.getUnitPrice());
        values.put("operatorId", grant.getOperatorId().toString());
        values.put("operatorUsername", grant.getOperatorName());
        values.put("authorizerId", grant.getAuthorizerId().toString());
        values.put("authorizerUsername", grant.getAuthorizerName());
        values.put("delegated", grant.isDelegated());
        if (sourceType != null) values.put("sourceType", sourceType);
        if (sourceId != null) values.put("sourceId", sourceId.toString());
        return Map.copyOf(values);
    }

    private static String token() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static BigDecimal positivePrice(BigDecimal value) {
        var normalized = Money.euros(Objects.requireNonNull(value, "unitPrice"));
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("temporary_price_must_be_greater_than_zero");
        }
        return normalized;
    }

    private static String cartLineId(String value) {
        var normalized = required(value, "cartLineId");
        if (normalized.length() > 128) throw new IllegalArgumentException("cartLineId");
        return normalized;
    }

    private static String sourceType(String value) {
        var normalized = required(value, "sourceType");
        if (normalized.length() > 48) throw new IllegalArgumentException("sourceType");
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field);
        return value.trim();
    }

    public record ClaimRequest(
            String cartLineId,
            UUID productId,
            BigDecimal unitPrice,
            String token) {
    }

    public record AuthorizationView(
            String token,
            Instant expiresAt,
            String authorizedBy,
            boolean delegated,
            long policyVersion) {
    }
}
