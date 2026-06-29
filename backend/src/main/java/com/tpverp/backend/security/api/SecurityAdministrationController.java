package com.tpverp.backend.security.api;

import com.tpverp.backend.security.application.SecurityAdministrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
public class SecurityAdministrationController {

    private final SecurityAdministrationService service;

    public SecurityAdministrationController(SecurityAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','GESTION_USUARIO')")
    public List<SecurityAdministrationService.UserItem> users() {
        return service.users();
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','GESTION_USUARIO')")
    public SecurityAdministrationService.UserItem createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return service.createUser(request.name(), request.userName(), request.password(), request.roleId());
    }

    @PutMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','GESTION_USUARIO')")
    public SecurityAdministrationService.UserItem changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeUserRole(userId, request.roleId());
    }

    @PatchMapping("/users/{userId}/active")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','GESTION_USUARIO')")
    public ResponseEntity<Void> setActive(
            @PathVariable UUID userId,
            @Valid @RequestBody ActiveRequest request) {
        service.setUserActive(userId, request.active());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/users/{userId}/name")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','GESTION_USUARIO')")
    public SecurityAdministrationService.UserItem changeUserName(
            @PathVariable UUID userId,
            @Valid @RequestBody UserNameRequest request) {
        return service.changeUserName(userId, request.userName());
    }

    @PutMapping("/users/{userId}/password")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('USERS_MANAGE','GESTION_USUARIO')")
    public ResponseEntity<Void> resetPassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(userId, request.password());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/stores")
    @PreAuthorize("hasRole('ADMIN')")
    public SecurityAdministrationService.UserItem replaceStoreAccess(
            @PathVariable UUID userId,
            @Valid @RequestBody StoreAccessRequest request) {
        return service.replaceStoreAccess(userId, request.storeIds());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ROLES_MANAGE','GESTION_USUARIO')")
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
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ROLES_MANAGE','GESTION_USUARIO')")
    public SecurityAdministrationService.RoleItem createRole(
            @Valid @RequestBody CreateRoleRequest request) {
        return service.createRole(request.name());
    }

    @PutMapping("/roles/{roleId}/permissions")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ROLES_MANAGE','GESTION_USUARIO')")
    public SecurityAdministrationService.RoleItem assignPermissions(
            @PathVariable UUID roleId,
            @Valid @RequestBody PermissionsRequest request) {
        return service.assignPermissions(roleId, request.codes());
    }

    public record CreateUserRequest(
            @NotBlank String name,
            @NotBlank String userName,
            @NotBlank @Pattern(regexp = "\\d{4,12}") String password,
            @NotNull UUID roleId) {
    }

    public record ChangeRoleRequest(@NotNull UUID roleId) {
    }

    public record ActiveRequest(boolean active) {
    }

    public record UserNameRequest(@NotBlank String userName) {
    }

    public record ResetPasswordRequest(@NotBlank @Pattern(regexp = "\\d{4,12}") String password) {
    }

    public record StoreAccessRequest(@NotNull Set<UUID> storeIds) {
    }

    public record CreateRoleRequest(@NotBlank String name) {
    }

    public record PermissionsRequest(@NotNull Set<String> codes) {
    }

    public record ChangeAdminPasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Pattern(regexp = "\\d{4,12}") String newPassword) {
    }
}
