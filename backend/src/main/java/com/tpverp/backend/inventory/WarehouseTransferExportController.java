package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/warehouse-transfers")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN', 'STOCK_TRANSFER')")
public class WarehouseTransferExportController {
    private final WarehouseTransferExportService service;

    public WarehouseTransferExportController(WarehouseTransferExportService service) {
        this.service = service;
    }

    @PostMapping("/export.xlsx")
    public ResponseEntity<byte[]> export(@Valid @RequestBody Request request) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("warehouse-transfer-" + request.date() + ".xlsx").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.export(request));
    }

    public record Request(@NotNull UUID sourceWarehouseId, @NotNull UUID targetWarehouseId,
                          @NotNull LocalDate date, @Size(max = 120) String externalNumber,
                          @Size(max = 4000) String notes, @Size(max = 64) String number,
                          @Pattern(regexp = "DRAFT|CONFIRMED|CANCELLED") String status,
                          WarehouseInputPriceSource priceSource,
                          @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal globalDiscount,
                          @NotEmpty @Size(max = 5000) List<@NotNull @Valid Line> lines,
                          @Size(max = 16) String locale) {}

    public record Line(@NotNull UUID productId,
                       @NotNull @Positive @Digits(integer = 16, fraction = 3) BigDecimal quantity,
                       @DecimalMin("0") @Digits(integer = 17, fraction = 3) BigDecimal unitPrice,
                       @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal discount,
                       @Size(max = 255) String productName, boolean priceOverridden) {}
}
