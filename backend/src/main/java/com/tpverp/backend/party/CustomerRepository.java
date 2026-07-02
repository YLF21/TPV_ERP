package com.tpverp.backend.party;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByCompanyIdOrderByFiscalName(UUID companyId);

    Optional<Customer> findByCompanyIdAndDocumentTypeAndDocumentNumber(
            UUID companyId, DocumentType documentType, String documentNumber);

    Optional<Customer> findByIdAndCompanyId(UUID id, UUID companyId);

    @Query(value = "select exists(select 1 from documento where cliente_id = :customerId)",
            nativeQuery = true)
    boolean hasDocumentHistory(UUID customerId);
}
