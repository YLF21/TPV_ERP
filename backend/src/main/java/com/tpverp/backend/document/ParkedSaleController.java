package com.tpverp.backend.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parked-sales")
public class ParkedSaleController {

    private final ParkedSaleService service;

    public ParkedSaleController(ParkedSaleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public List<ParkedSaleView> list() {
        return service.list().stream().map(ParkedSaleView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleView park(
            @Valid @RequestBody ParkRequest request,
            Authentication authentication) {
        return ParkedSaleView.from(service.park(
                request.document().toCommand(), request.comment(), request.printMode(),
                request.document().operationAuthorizations(),
                authentication));
    }

    @PostMapping("/from-pos")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleView parkFromPos(
            @Valid @RequestBody PosParkRequest request,
            Authentication authentication) {
        return ParkedSaleView.from(service.parkFromPos(
                request.sale(),
                request.comment(),
                request.printMode(),
                authentication));
    }

    @PostMapping("/{id}/open")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleOpened open(@PathVariable UUID id) {
        return service.open(id);
    }

    @PostMapping("/{id}/recoveries")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleService.ParkedSaleRecoveryView recover(
            @PathVariable UUID id,
            @Valid @RequestBody RecoveryRequest request,
            Authentication authentication) {
        return service.recover(id, request.recoveryId(), authentication);
    }

    @PostMapping("/{id}/recoveries/{recoveryId}/acknowledge")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public ParkedSaleService.ParkedSaleRecoveryView acknowledge(
            @PathVariable UUID id,
            @PathVariable UUID recoveryId,
            Authentication authentication) {
        return service.acknowledge(id, recoveryId, authentication);
    }

    @PostMapping("/{id}/deletions")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public void delete(
            @PathVariable UUID id,
            Authentication authentication) {
        service.delete(id, authentication);
    }

    @PostMapping("/deletions")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS') or hasAuthority('VENTA')")
    public DeleteAllResponse deleteAll(
            @Valid @RequestBody DeleteAllRequest request,
            Authentication authentication) {
        return new DeleteAllResponse(service.deleteAll(
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication));
    }

    public record ParkRequest(
            @NotNull @Valid DocumentRequest document,
            String comment,
            SalePrintMode printMode) {
        public ParkRequest(DocumentRequest document, String comment) {
            this(document, comment, SalePrintMode.DEFAULT);
        }
    }

    public record PosParkRequest(
            @NotNull @Valid PosCashController.SaleRequest sale,
            String comment,
            SalePrintMode printMode) {
        public PosParkRequest(PosCashController.SaleRequest sale, String comment) {
            this(sale, comment, SalePrintMode.DEFAULT);
        }
    }

    public record RecoveryRequest(@NotNull UUID recoveryId) {}

    public record DeleteAllRequest(
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotBlank @Size(max = 128) String authorizerPassword) {

        @Override
        public String toString() {
            return "DeleteAllRequest[authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record DeleteAllResponse(int deletedCount) {}
}
