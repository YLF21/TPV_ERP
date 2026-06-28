package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/families")
public class FamilyController {

    private final CatalogService service;

    public FamilyController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + GESTION_PRODUCTO + "')")
    public List<Family> list() {
        return service.families();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Family create(@Valid @RequestBody WarehouseController.NameRequest request) {
        return service.createFamily(request.name());
    }

    @PutMapping("/{familyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Family rename(
            @PathVariable UUID familyId, @Valid @RequestBody WarehouseController.NameRequest request) {
        return service.renameFamily(familyId, request.name());
    }

    @DeleteMapping("/{familyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ResponseEntity<Void> delete(@PathVariable UUID familyId) {
        service.deleteFamily(familyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{familyId}/subfamilies")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Subfamily createSubfamily(
            @PathVariable UUID familyId, @Valid @RequestBody WarehouseController.NameRequest request) {
        return service.createSubfamily(familyId, request.name());
    }

    @PutMapping("/subfamilies/{subfamilyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Subfamily renameSubfamily(
            @PathVariable UUID subfamilyId, @Valid @RequestBody WarehouseController.NameRequest request) {
        return service.renameSubfamily(subfamilyId, request.name());
    }

    @DeleteMapping("/subfamilies/{subfamilyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ResponseEntity<Void> deleteSubfamily(@PathVariable UUID subfamilyId) {
        service.deleteSubfamily(subfamilyId);
        return ResponseEntity.noContent().build();
    }
}
