package com.tpverp.backend.pdawork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pda-work")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_ALMACEN', 'STOCK_ADJUST', 'STOCK_TRANSFER')")
public class PdaWorkController {
    private final PdaWorkService service;
    public PdaWorkController(PdaWorkService service){this.service=service;}
    @GetMapping public List<PdaWorkView> list(@RequestParam(required=false) PdaWorkType type,@RequestParam(required=false) PdaWorkStatus status){return service.list(type,status);}
    @PostMapping public PdaWorkView create(@Valid @RequestBody CreateRequest request,Authentication authentication){return service.create(request.command(),authentication);}
    @PostMapping("/{id}/finish") public PdaWorkView finish(@PathVariable UUID id,Authentication authentication){return service.finish(id,authentication);}
    @PostMapping("/{id}/cancel") public PdaWorkView cancel(@PathVariable UUID id,Authentication authentication){return service.cancel(id,authentication);}
    public record CreateRequest(@NotNull PdaWorkType type,@NotBlank @Size(max=180) String title,@Size(max=120) String reference,
            @Size(max=120) String productCode,UUID warehouseId,BigDecimal quantity,@Size(max=120) String lotNumber,LocalDate expiryDate,
            @Size(max=120) String location,@Size(max=16) String priority,@Size(max=4000) String notes,@Size(max=240) String evidenceName,
            @Size(max=120) String evidenceType,String evidenceData){PdaWorkService.CreateCommand command(){return new PdaWorkService.CreateCommand(type,title,reference,productCode,warehouseId,quantity,lotNumber,expiryDate,location,priority,notes,evidenceName,evidenceType,evidenceData);}}
}