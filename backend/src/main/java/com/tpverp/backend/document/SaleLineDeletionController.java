package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sale-line-deletions")
public class SaleLineDeletionController {

    private final SaleLineDeletionService service;

    public SaleLineDeletionController(SaleLineDeletionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<SaleLineDeletionView> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public List<SaleLineDeletionView> record(
            @Valid @RequestBody Request request,
            Authentication authentication) {
        return service.record(
                request.saleOperationId(), request.deletionOperationId(),
                request.toCommands(), request.fullTicketClear(), authentication);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record Request(
            @NotNull UUID saleOperationId,
            @NotNull UUID deletionOperationId,
            boolean fullTicketClear,
            @NotEmpty List<@Valid Line> lines) {

        List<SaleLineDeletionCommand> toCommands() {
            return lines.stream().map(Line::toCommand).toList();
        }
    }

    public record Line(
            @NotNull UUID productId,
            @NotNull String code,
            @NotNull String name,
            int quantity,
            @NotNull BigDecimal unitPrice) {

        SaleLineDeletionCommand toCommand() {
            return new SaleLineDeletionCommand(productId, code, name, quantity, unitPrice);
        }
    }
}
