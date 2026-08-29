package com.tpverp.backend.pdawork;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface PdaStockLotRepository extends JpaRepository<PdaStockLot,UUID>{
 List<PdaStockLot> findByStoreIdAndWarehouseIdAndProductCodeIgnoreCaseAndQuantityGreaterThanOrderByExpiryDateAscReceivedAtAsc(UUID storeId,UUID warehouseId,String productCode,java.math.BigDecimal quantity);
 Optional<PdaStockLot> findByIdAndStoreId(UUID id,UUID storeId);
}
