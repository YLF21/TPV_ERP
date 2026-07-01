package com.tpverp.saas.sync;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasSyncEventRepository extends JpaRepository<SaasSyncEvent, UUID> {
}
