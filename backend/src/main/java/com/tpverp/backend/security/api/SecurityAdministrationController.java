package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.SecurityAdministrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','ROLES_MANAGE')")
public class SecurityAdministrationController {

    private final SecurityAdministrationService service;

    public SecurityAdministrationController(SecurityAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public List<SecurityAdministrationService.UserItem> users() {
        return service.users();
    }

    @PostMapping("/users")
    public SecurityAdministrationService.UserItem createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return service.createUser(request.name(), request.password(), request.roleId());
    }

    @PutMapping("/users/{userId}/role")
    public SecurityAdministrationService.UserItem changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeUserRole(userId, request.roleId());
    }

    @PatchMapping("/users/{userId}/active")
    public ResponseEntity<Void> setActive(
            @PathVariable UUID userId,
            @Valid @RequestBody ActiveRequest request) {
        service.setUserActive(userId, request.active());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles")
    public List<SecurityAdministrationService.RoleItem> roles() {
        return service.roles();
    }

    @PutMapping("/users/admin/password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> changeAdminPassword(
            @Valid @RequestBody ChangeAdminPasswordRequest request) {
        service.changeAdminPassword(request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/roles")
    public SecurityAdministrationService.RoleItem createRole(
            @Valid @RequestBody CreateRoleRequest request) {
        return service.createRole(request.name());
    }

    @PutMapping("/roles/{roleId}/permissions")
    public SecurityAdministrationService.RoleItem assignPermissions(
            @PathVariable UUID roleId,
            @Valid @RequestBody PermissionsRequest request) {
        return service.assignPermissions(roleId, request.codes());
    }

    public record CreateUserRequest(
            @NotBlank String name,
            @NotBlank String password,
            @NotNull UUID roleId) {
    }

    public record ChangeRoleRequest(@NotNull UUID roleId) {
    }

    public record ActiveRequest(boolean active) {
    }

    public record CreateRoleRequest(@NotBlank String name) {
    }

    public record PermissionsRequest(@NotNull Set<String> codes) {
    }

    public record ChangeAdminPasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8) String newPassword) {
    }
}
