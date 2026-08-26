package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

class FiscalRequiredSubmissionRepositoryContractTest {

    @Test
    void lookupDeExportacionUsaBloqueoPesimistaYConservaElAlcanceTenant() throws Exception {
        var method = FiscalRequiredSubmissionRepository.class.getMethod(
                "findForUpdateByIdAndCompanyIdAndInstallationId",
                UUID.class, UUID.class, UUID.class);

        assertThat(method.getAnnotation(Lock.class)).isNotNull();
        assertThat(method.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);

        var export = FiscalRequiredSubmissionService.class.getMethod(
                "export", UUID.class, FiscalExportKind.class,
                java.time.OffsetDateTime.class, java.time.OffsetDateTime.class);
        assertThat(export.getAnnotation(Transactional.class)).isNotNull();
    }
}
