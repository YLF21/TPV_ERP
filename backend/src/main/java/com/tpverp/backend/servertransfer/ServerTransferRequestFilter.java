package com.tpverp.backend.servertransfer;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class ServerTransferRequestFilter extends OncePerRequestFilter {
    private final ServerTransferFence fence;
    public ServerTransferRequestFilter(ServerTransferFence fence) { this.fence=fence; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String path=request.getRequestURI();
        if (path.startsWith("/api/v1/server-transfer/") || path.equals("/actuator/health")) { chain.doFilter(request,response); return; }
        if (!fence.enter()) {
            response.setStatus(423); response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":423,\"code\":\"SERVER_TRANSFER_FROZEN\",\"detail\":\"Servidor bloqueado por traslado planificado\"}");
            return;
        }
        try { chain.doFilter(request,response); } finally { fence.leave(); }
    }
}
