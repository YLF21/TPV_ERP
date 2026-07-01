package com.tpverp.saas.license;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasStoreRepository extends JpaRepository<SaasStore, UUID> {
}
