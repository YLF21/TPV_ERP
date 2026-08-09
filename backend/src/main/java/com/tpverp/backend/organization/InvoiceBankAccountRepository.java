package com.tpverp.backend.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceBankAccountRepository extends JpaRepository<InvoiceBankAccount, UUID> {
    List<InvoiceBankAccount> findAllByCompanyIdOrderByOrdenAscIdAsc(UUID companyId);
    List<InvoiceBankAccount> findAllByCompanyIdAndActivaTrueOrderByOrdenAscIdAsc(UUID companyId);
    Optional<InvoiceBankAccount> findByIdAndCompanyId(UUID id, UUID companyId);
    boolean existsByCompanyIdAndIban(UUID companyId, String iban);
    long countByCompanyId(UUID companyId);
}
