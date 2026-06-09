package com.tpverp.backend.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    List<Supplier> findByCompanyIdOrderByDocumentNumberAsc(UUID companyId);

    Optional<Supplier> findByCompanyIdAndDocumentTypeAndDocumentNumber(
            UUID companyId, DocumentType documentType, String documentNumber);

    Optional<Supplier> findByIdAndCompanyId(UUID id, UUID companyId);

    @Query(value = """
            select exists(select 1 from documento where proveedor_id = :supplierId)
                or exists(select 1 from producto_proveedor where proveedor_id = :supplierId)
            """, nativeQuery = true)
    boolean hasHistory(UUID supplierId);
}
