package com.tpverp.backend.terminal.discovery;

import com.tpverp.backend.security.gestion.GestionGroup;
import com.tpverp.backend.security.gestion.RequireGestionGroup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/terminals/server-connection")
public class ServerConnectionInfoController {
    private final ServerConnectionInfoService service;

    public ServerConnectionInfoController(ServerConnectionInfoService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TERMINALS_MANAGE')")
    @RequireGestionGroup(GestionGroup.SEGURIDAD)
    public ServerConnectionInfoService.ConnectionInfo read(HttpServletRequest request) {
        // The accepted socket's port remains the backend port behind HTTPS and forwarding headers.
        return service.read(request.getLocalPort());
    }
}
