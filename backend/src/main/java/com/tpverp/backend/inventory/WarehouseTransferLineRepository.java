package com.tpverp.backend.inventory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WarehouseTransferLineRepository extends JpaRepository<WarehouseTransferLine, UUID> {
    List<WarehouseTransferLine> findByTransferIdOrderByCode(UUID transferId);
    @Query("""
            select new com.tpverp.backend.inventory.WarehouseTransferLineTotals(
                line.transferId, count(line), sum(line.quantity))
            from WarehouseTransferLine line
            where line.transferId in :transferIds
            group by line.transferId
            """)
    List<WarehouseTransferLineTotals> totalsForTransfers(List<UUID> transferIds);
    void deleteByTransferId(UUID transferId);
}
