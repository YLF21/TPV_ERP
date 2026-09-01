package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PRODUCTS_WRITE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import jakarta.validation.Valid;
import com.tpverp.backend.shared.api.PagedResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/families")
public class FamilyController {

    private final CatalogService service;

    public FamilyController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public List<Family> list() {
        return service.families();
    }

    @GetMapping("/resolve")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public CatalogService.FamilyResolution resolve(@RequestParam String code) {
        return service.resolve(code);
    }

    @GetMapping("/next-code")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public CatalogService.NextFamilyCode nextCode() {
        return new CatalogService.NextFamilyCode(service.nextFamilyCode());
    }

    @GetMapping("/{familyId}/subfamilies")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public List<Subfamily> listSubfamilies(@PathVariable UUID familyId) {
        return service.subfamilies(familyId);
    }

    @GetMapping("/{familyId}/subfamilies/next-suffix")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public CatalogService.NextSubfamilySuffix nextSuffix(@PathVariable UUID familyId) {
        return new CatalogService.NextSubfamilySuffix(service.nextSubfamilySuffix(familyId));
    }

    @GetMapping("/products")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public PagedResult<FamilyProductView> products(
            @RequestParam(required = false) UUID familyId,
            @RequestParam(required = false) UUID subfamilyId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection) {
        return service.familyProducts(
                familyId, subfamilyId, limit, cursor, sortBy, sortDirection);
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_READ + "','" + PRODUCTS_WRITE
            + "','" + GESTION_PRODUCTO + "','" + GESTION_VENTAS + "','" + STOCK_READ + "','" + VENTA + "')")
    public PagedResult<FamilyHierarchySearchView> search(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.searchHierarchy(q, limit, cursor);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Family create(@Valid @RequestBody FamilyRequest request) {
        return service.createFamily(request.name(), request.familyCode());
    }

    @PutMapping("/{familyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Family rename(
            @PathVariable UUID familyId, @Valid @RequestBody FamilyRequest request) {
        return service.renameFamily(familyId, request.name(), request.familyCode());
    }

    @DeleteMapping("/{familyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID familyId,
            @RequestParam(defaultValue = "false") boolean confirmProductReassignment) {
        service.deleteFamily(familyId, confirmProductReassignment);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{familyId}/delete-impact")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public CatalogService.DeleteImpact familyDeleteImpact(@PathVariable UUID familyId) {
        return service.familyDeleteImpact(familyId);
    }

    @PostMapping("/{familyId}/subfamilies")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Subfamily createSubfamily(
            @PathVariable UUID familyId, @Valid @RequestBody SubfamilyRequest request) {
        return service.createSubfamily(familyId, request.name(), request.subfamilySuffix());
    }

    @PutMapping("/subfamilies/{subfamilyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public Subfamily renameSubfamily(
            @PathVariable UUID subfamilyId, @Valid @RequestBody SubfamilyRequest request) {
        return service.renameSubfamily(subfamilyId, request.name(), request.subfamilySuffix());
    }

    @DeleteMapping("/subfamilies/{subfamilyId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public ResponseEntity<Void> deleteSubfamily(
            @PathVariable UUID subfamilyId,
            @RequestParam(defaultValue = "false") boolean confirmProductCleanup) {
        service.deleteSubfamily(subfamilyId, confirmProductCleanup);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/subfamilies/{subfamilyId}/delete-impact")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + PRODUCTS_WRITE + "','" + GESTION_PRODUCTO + "')")
    public CatalogService.DeleteImpact subfamilyDeleteImpact(@PathVariable UUID subfamilyId) {
        return service.subfamilyDeleteImpact(subfamilyId);
    }

    public record FamilyRequest(
            @jakarta.validation.constraints.NotBlank String name,
            String familyCode) {
    }

    public record SubfamilyRequest(
            @jakarta.validation.constraints.NotBlank String name,
            String subfamilySuffix) {
    }

}
