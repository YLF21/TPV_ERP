package com.tpverp.backend.security.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales/operation-security")
public class SaleOperationSecurityController {

    private static final String APP_VENTA_ACCESS =
            "hasRole('ADMIN') or hasAnyAuthority("
                    + "'CONFIGURACION_TERMINAL','VENTA','GESTION_VENTAS',"
                    + "'GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS',"
                    + "'TICKETS_CREATE','INVOICES_WRITE')";

    private final SaleOperationSecurityService service;

    public SaleOperationSecurityController(SaleOperationSecurityService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(APP_VENTA_ACCESS)
    public SaleOperationSecurityService.ConfigurationView current() {
        return service.current();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public SaleOperationSecurityService.ConfigurationView update(
            @Valid @RequestBody UpdateRequest request) {
        return service.update(
                request.expectedVersion(),
                request.operations().stream().map(OperationRequest::toSetting).toList());
    }

    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public SaleOperationSecurityService.ConfigurationView reset(
            @Valid @RequestBody ResetRequest request) {
        return service.reset(request.expectedVersion());
    }

    public record UpdateRequest(
            @NotNull @Min(0) Long expectedVersion,
            @NotEmpty
            List<@NotNull @Valid OperationRequest> operations) {
    }

    public record OperationRequest(
            @NotNull SaleOperationCode code,
            @NotNull Boolean requirePermission,
            @NotNull Boolean requirePassword) {

        SaleOperationSecurityService.OperationSetting toSetting() {
            return new SaleOperationSecurityService.OperationSetting(
                    code,
                    requirePermission,
                    requirePassword);
        }
    }

    public record ResetRequest(@NotNull @Min(0) Long expectedVersion) {
    }
}
