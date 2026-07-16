package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;

import com.tpverp.backend.shared.api.PagedResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouse-outputs")
public class WarehouseOutputController {

    private final WarehouseOutputService service;

    public WarehouseOutputController(WarehouseOutputService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public PagedResult<WarehouseOutputView> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.listPage(limit, cursor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public WarehouseOutput create(
            @Valid @RequestBody WarehouseOutputCommand command,
            Authentication authentication) {
        return service.create(command, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public WarehouseOutput update(
            @PathVariable UUID id,
            @Valid @RequestBody WarehouseOutputCommand command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + GESTION_ALMACEN + "')")
    public WarehouseOutput confirm(
            @PathVariable UUID id, Authentication authentication) {
        return service.confirm(id, authentication);
    }
}
