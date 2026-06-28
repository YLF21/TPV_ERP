package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_OUTPUTS_CONFIRM;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_OUTPUTS_DELETE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_OUTPUTS_EDIT;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_OUTPUTS_READ;

import jakarta.validation.Valid;
import java.util.List;
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
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_OUTPUTS_READ + "','" + GESTION_PRODUCTO + "')")
    public List<WarehouseOutput> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_OUTPUTS_EDIT + "','" + GESTION_PRODUCTO + "')")
    public WarehouseOutput create(
            @Valid @RequestBody WarehouseOutputCommand command,
            Authentication authentication) {
        return service.create(command, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_OUTPUTS_EDIT + "','" + GESTION_PRODUCTO + "')")
    public WarehouseOutput update(
            @PathVariable UUID id,
            @Valid @RequestBody WarehouseOutputCommand command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_OUTPUTS_DELETE + "','" + GESTION_PRODUCTO + "')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_OUTPUTS_CONFIRM + "','" + GESTION_PRODUCTO + "')")
    public WarehouseOutput confirm(
            @PathVariable UUID id, Authentication authentication) {
        return service.confirm(id, authentication);
    }
}
