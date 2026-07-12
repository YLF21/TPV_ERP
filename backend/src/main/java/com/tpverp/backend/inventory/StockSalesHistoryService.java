package com.tpverp.backend.inventory;

import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockSalesHistoryService {

    private final CurrentOrganization organization;
    private final CommercialDocumentRepository documents;

    public StockSalesHistoryService(
            CurrentOrganization organization, CommercialDocumentRepository documents) {
        this.organization = organization;
        this.documents = documents;
    }

    @Transactional(readOnly = true)
    public List<StockSalesHistoryRow> history(
            UUID productId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(productId, "productId");
        var start = from;
        var end = to;
        if (start != null && end != null && start.isAfter(end)) {
            start = to;
            end = from;
        }
        return documents.findProductSalesHistory(
                        organization.currentStore().getId(), productId, start, end)
                .stream()
                .map(StockSalesHistoryService::toView)
                .toList();
    }

    private static StockSalesHistoryRow toView(
            CommercialDocumentRepository.SalesHistoryProjection row) {
        return new StockSalesHistoryRow(
                row.getDocumentId(),
                CommercialDocumentType.valueOf(row.getDocumentType()),
                row.getDocumentNumber(),
                DocumentStatus.valueOf(row.getStatus()),
                row.getOccurredAt(),
                row.getCustomerId(),
                row.getCustomerName(),
                row.getQuantity(),
                row.getUnitPrice(),
                row.getDiscountPercent(),
                row.getLineTotal(),
                row.getUserId(),
                row.getUserName(),
                row.getStoreId(),
                row.getStoreName(),
                row.getWarehouseId(),
                row.getWarehouseName());
    }
}
