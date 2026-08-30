package com.tpverp.backend.pdawork;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.math.BigDecimal;import java.time.LocalDate;import java.util.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/pda-inventory") @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN','STOCK_ADJUST','STOCK_TRANSFER')")
public class PdaInventoryTraceController{
 private final PdaInventoryTraceService service;public PdaInventoryTraceController(PdaInventoryTraceService service){this.service=service;}
 @GetMapping("/locations")public List<PdaInventoryTraceService.LocationView> locations(@RequestParam UUID warehouseId){return service.locations(warehouseId);}
 @PostMapping("/locations")public PdaInventoryTraceService.LocationView location(@Valid @RequestBody LocationRequest r){return service.createLocation(r.warehouseId(),r.code(),r.description());}
 @PostMapping("/locations/{id}/deactivate")public PdaInventoryTraceService.LocationView deactivate(@PathVariable UUID id,@Valid @RequestBody VersionRequest r){return service.deactivateLocation(id,r.version());}
 @PostMapping("/lots")public PdaInventoryTraceService.LotView receive(@Valid @RequestBody LotRequest r){return service.receive(new PdaInventoryTraceService.ReceiveLot(r.warehouseId(),r.productId(),r.productCode(),r.lotNumber(),r.expiryDate(),r.supplierId(),r.supplierReference(),r.quantity()));}
 @GetMapping("/lots")public List<PdaInventoryTraceService.LotView> lots(@RequestParam UUID warehouseId,@RequestParam String productCode,@RequestParam(required=false)LocalDate expiresBefore){return service.lots(warehouseId,productCode,expiresBefore);}
 @GetMapping("/lots/fefo")public List<PdaInventoryTraceService.FefoAllocation> fefo(@RequestParam UUID warehouseId,@RequestParam String productCode,@RequestParam @Positive BigDecimal quantity){return service.planFefo(warehouseId,productCode,quantity);}
 @PostMapping("/lots/{id}/consume")public PdaInventoryTraceService.LotView consume(@PathVariable UUID id,@Valid @RequestBody ConsumeRequest r){return service.consume(id,r.quantity(),r.version());}
 public record LocationRequest(@NotNull UUID warehouseId,@NotBlank @Size(max=120)String code,@Size(max=240)String description){}public record VersionRequest(@PositiveOrZero long version){}
 public record LotRequest(@NotNull UUID warehouseId,UUID productId,@NotBlank @Size(max=120)String productCode,@NotBlank @Size(max=120)String lotNumber,LocalDate expiryDate,UUID supplierId,@Size(max=120)String supplierReference,@NotNull @PositiveOrZero BigDecimal quantity){}
 public record ConsumeRequest(@NotNull @Positive BigDecimal quantity,@PositiveOrZero long version){}
}
