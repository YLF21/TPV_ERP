package com.tpverp.backend.inventory;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keeps supplier-import audit events independent from the document transaction. */
@Service
public class WarehouseInputExcelAuditService {

    private final AuditService audit;

    public WarehouseInputExcelAuditService(AuditService audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditResult result, Map<String, Object> details) {
        audit.record("WAREHOUSE_INPUT_EXCEL_SUPPLIER_UPDATE", result, details);
    }
}
