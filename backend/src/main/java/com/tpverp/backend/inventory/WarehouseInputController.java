package com.tpverp.backend.inventory;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_INPUTS_CONFIRM;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_INPUTS_DELETE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_INPUTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.WAREHOUSE_INPUTS_WRITE;

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
@RequestMapping("/api/v1/warehouse-inputs")
public class WarehouseInputController {

    private final WarehouseInputService service;

    public WarehouseInputController(WarehouseInputService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_INPUTS_READ + "','" + WAREHOUSE_INPUTS_WRITE + "','"
            + WAREHOUSE_INPUTS_DELETE + "','" + WAREHOUSE_INPUTS_CONFIRM + "','" + GESTION_PRODUCTO + "')")
    public List<WarehouseInputView> list() {
        return service.list().stream().map(WarehouseInputView::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_INPUTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public WarehouseInput create(
            @Valid @RequestBody WarehouseInputCommand command,
            Authentication authentication) {
        return service.create(command, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_INPUTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public WarehouseInput update(
            @PathVariable UUID id,
            @Valid @RequestBody WarehouseInputCommand command) {
        return service.update(id, command);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_INPUTS_DELETE + "','" + GESTION_PRODUCTO + "')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + WAREHOUSE_INPUTS_CONFIRM + "','" + GESTION_PRODUCTO + "')")
    public WarehouseInput confirm(
            @PathVariable UUID id,
            Authentication authentication) {
        return service.confirm(id, authentication);
    }
}
