package com.tpverp.backend.verifactu;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedVerifactuCertificateRepository
        extends JpaRepository<ManagedVerifactuCertificate, UUID> {

    Optional<ManagedVerifactuCertificate> findByCompanyIdAndStatus(
            UUID companyId, ManagedCertificateStatus status);

    List<ManagedVerifactuCertificate> findAllByStatusAndReplacedAtBefore(
            ManagedCertificateStatus status, Instant limit);

    List<ManagedVerifactuCertificate> findAllByStatus(ManagedCertificateStatus status);
}
