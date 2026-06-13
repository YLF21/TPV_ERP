package com.tpverp.backend.organization;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmpresaRepository extends JpaRepository<Empresa, UUID> {

    List<Empresa> findByTaxId(String taxId);
}
