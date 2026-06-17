package com.tpverp.backend.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    Optional<Voucher> findByTiendaIdAndCode(UUID tiendaId, String code);

    List<Voucher> findAllByTiendaIdOrderByCreatedAtDesc(UUID tiendaId);
}
