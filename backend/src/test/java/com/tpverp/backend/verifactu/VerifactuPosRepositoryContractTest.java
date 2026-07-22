package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class VerifactuPosRepositoryContractTest {

    @Test
    void posQueriesJoinDocumentsAndEnforceCompanyStoreAndTerminalScope() {
        for (String methodName : List.of("findPosQueue", "countPosQueueByStatusIn")) {
            var method = Arrays.stream(FiscalSubmissionStateRepository.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            var query = method.getAnnotation(Query.class).value();

            assertThat(query)
                    .contains("join CommercialDocument")
                    .contains("record.companyId = :companyId")
                    .contains("record.storeId = :storeId")
                    .contains("commercialDocument.tiendaId = :storeId")
                    .contains("commercialDocument.terminalOrigenId = :terminalId")
                    .doesNotContain("clienteId", "snapshot", "issuerTaxId");
        }
        var queueMethod = Arrays.stream(FiscalSubmissionStateRepository.class.getMethods())
                .filter(candidate -> candidate.getName().equals("findPosQueue"))
                .findFirst()
                .orElseThrow();
        assertThat(queueMethod.getAnnotation(Query.class).value())
                .contains("state.status in :statuses")
                .contains("state.updatedAt desc, record.sequence desc, record.id desc");
    }
}
