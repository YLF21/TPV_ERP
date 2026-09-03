package com.tpverp.backend.security.gestion;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/gestion-groups")
public class GestionGroupUnlockController {

    private final GestionGroupAccessService access;

    public GestionGroupUnlockController(GestionGroupAccessService access) {
        this.access = access;
    }

    @PostMapping("/{group}/unlock")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('APP_GESTION_ACCESS')")
    public GestionGroupAccessService.UnlockResult unlock(
            @PathVariable String group,
            @RequestBody UnlockRequest request,
            Authentication authentication) {
        return access.unlock(GestionGroup.parse(group), request.password(), authentication);
    }

    public record UnlockRequest(String password) {
    }
}
