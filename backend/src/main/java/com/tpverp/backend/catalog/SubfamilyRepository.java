package com.tpverp.backend.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubfamilyRepository extends JpaRepository<Subfamily, UUID> {

    List<Subfamily> findByFamilyIdOrderByNombre(UUID familyId);
}
