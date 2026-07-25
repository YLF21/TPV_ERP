package com.tpverp.backend.security.api;

import com.tpverp.backend.catalog.ProductEditAuthorizationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class ProductEditAuthorizationFilter extends OncePerRequestFilter {

    private static final Pattern MANAGEMENT =
            Pattern.compile("^/api/v1/products/management/([0-9a-fA-F-]{36})$");
    private static final Pattern IMAGE =
            Pattern.compile("^/api/v1/products/([0-9a-fA-F-]{36})/image$");
    private static final Pattern SUPPLIERS =
            Pattern.compile("^/api/v1/products/([0-9a-fA-F-]{36})/suppliers(?:/[0-9a-fA-F-]{36})?$");

    private final ProductEditAuthorizationService authorizations;

    ProductEditAuthorizationFilter(ProductEditAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var original = SecurityContextHolder.getContext().getAuthentication();
        var operationId = operationId(request);
        var productId = authorizedProductId(request);
        ProductEditAuthorizationService.Grant grant = null;
        if (original != null && operationId.isPresent() && productId.isPresent()) {
            grant = authorizations.validGrant(operationId.get(), productId.get(), original).orElse(null);
        }
        if (grant == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var authorities = new ArrayList<GrantedAuthority>(original.getAuthorities());
        authorities.add(new SimpleGrantedAuthority("GESTION_PRODUCTO"));
        var augmented = new UsernamePasswordAuthenticationToken(
                original.getPrincipal(), original.getCredentials(), authorities);
        augmented.setDetails(original.getDetails());
        SecurityContextHolder.getContext().setAuthentication(augmented);
        try {
            filterChain.doFilter(request, response);
            if (isMutation(request) && response.getStatus() < 400) {
                authorizations.recordMutation(grant, mutation(request), augmented);
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(original);
        }
    }

    private static Optional<UUID> operationId(HttpServletRequest request) {
        var value = request.getHeader(ProductEditAuthorizationService.HEADER);
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> authorizedProductId(HttpServletRequest request) {
        var method = request.getMethod();
        var path = request.getRequestURI();
        var matcher = MANAGEMENT.matcher(path);
        if (matcher.matches() && ("GET".equals(method) || "PUT".equals(method))) {
            return uuid(matcher.group(1));
        }
        matcher = IMAGE.matcher(path);
        if (matcher.matches() && ("GET".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return uuid(matcher.group(1));
        }
        matcher = SUPPLIERS.matcher(path);
        if (matcher.matches() && ("GET".equals(method) || "POST".equals(method)
                || "PUT".equals(method) || "DELETE".equals(method))) {
            return uuid(matcher.group(1));
        }
        return Optional.empty();
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean isMutation(HttpServletRequest request) {
        return !"GET".equals(request.getMethod());
    }

    private static String mutation(HttpServletRequest request) {
        var path = request.getRequestURI();
        if (path.endsWith("/image")) return "DELETE".equals(request.getMethod()) ? "IMAGE_DELETE" : "IMAGE_UPDATE";
        if (path.contains("/suppliers")) return "SUPPLIER_" + request.getMethod();
        return "PRODUCT_UPDATE";
    }
}
