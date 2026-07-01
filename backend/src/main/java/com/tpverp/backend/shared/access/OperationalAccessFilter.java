package com.tpverp.backend.shared.access;

import com.tpverp.backend.installation.InstallationStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class OperationalAccessFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final InstallationStatusService statusService;
    private final OperationalAccessPolicy accessPolicy;

    public OperationalAccessFilter(
            InstallationStatusService statusService,
            OperationalAccessPolicy accessPolicy) {
        this.statusService = statusService;
        this.accessPolicy = accessPolicy;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        OperationCategory category = category(request);
        OperationalMode mode = statusService.status().mode();
        if (!accessPolicy.isAllowed(mode, category)) {
            response.setStatus(423);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("""
                    {"type":"urn:tpv-erp:error:LICENSE_REQUIRED","title":"Locked",\
                    "status":423,"detail":"La demo ha caducado y no hay una licencia valida",\
                    "code":"LICENSE_REQUIRED"}""");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private OperationCategory category(HttpServletRequest request) {
        if (READ_METHODS.contains(request.getMethod())) {
            return OperationCategory.READ;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/licenses")) {
            return OperationCategory.LICENSE_MANAGEMENT;
        }
        if (path.startsWith("/api/v1/license/validate")) {
            return OperationCategory.LICENSE_MANAGEMENT;
        }
        if (path.startsWith("/api/v1/backups")) {
            return OperationCategory.BACKUP_OR_RESTORE;
        }
        if (path.startsWith("/api/v1/auth")) {
            return OperationCategory.READ;
        }
        if (path.startsWith("/api/v1/users") || path.startsWith("/api/v1/roles")) {
            return OperationCategory.SECURITY_WRITE;
        }
        if (path.startsWith("/api/v1/terminals")) {
            return OperationCategory.TERMINAL_WRITE;
        }
        return OperationCategory.BUSINESS_WRITE;
    }
}
