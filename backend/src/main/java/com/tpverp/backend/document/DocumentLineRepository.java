package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentLineRepository extends JpaRepository<DocumentLine, UUID> {
}
