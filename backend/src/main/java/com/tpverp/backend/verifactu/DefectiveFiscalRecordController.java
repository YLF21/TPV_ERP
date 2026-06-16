package com.tpverp.backend.verifactu;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/verifactu/defective-records")
public class DefectiveFiscalRecordController {

    private final DefectiveFiscalRecordService service;

    public DefectiveFiscalRecordController(DefectiveFiscalRecordService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
    public List<DefectiveFiscalRecordView> list() {
        return service.list();
    }
}
