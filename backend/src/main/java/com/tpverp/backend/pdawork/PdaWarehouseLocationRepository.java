package com.tpverp.backend.pdawork;
import java.util.*;import org.springframework.data.jpa.repository.JpaRepository;
public interface PdaWarehouseLocationRepository extends JpaRepository<PdaWarehouseLocation,UUID>{
 List<PdaWarehouseLocation> findByStoreIdAndWarehouseIdAndActiveTrueOrderByCode(UUID storeId,UUID warehouseId);
 Optional<PdaWarehouseLocation> findByStoreIdAndWarehouseIdAndCodeIgnoreCaseAndActiveTrue(UUID storeId,UUID warehouseId,String code);
}
