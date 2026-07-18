package com.tpverp.backend.promotion;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','STOCK_READ')";
    private static final String MANAGE_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('GESTION_PRODUCTO')";

    private final PromotionService promotions;

    public PromotionController(PromotionService promotions) {
        this.promotions = promotions;
    }

    @GetMapping
    @PreAuthorize(READ_PERMISSION)
    public List<PromotionService.PromotionView> list() {
        return promotions.list();
    }

    @PostMapping("")
    @PreAuthorize(MANAGE_PERMISSION)
    public PromotionService.PromotionView create(@Valid @RequestBody PromotionService.PromotionRequest request) {
        return promotions.create(request);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_VENTAS','VENTA')")
    public PromotionPreview preview(@Valid @RequestBody PromotionPreviewRequest request) {
        return promotions.preview(request);
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize(MANAGE_PERMISSION)
    public PromotionService.PromotionView duplicate(@PathVariable UUID id) {
        return promotions.duplicate(id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize(MANAGE_PERMISSION)
    public PromotionService.PromotionView activate(@PathVariable UUID id) {
        return promotions.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize(MANAGE_PERMISSION)
    public PromotionService.PromotionView deactivate(@PathVariable UUID id) {
        return promotions.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE_PERMISSION)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        promotions.delete(id);
    }
}
