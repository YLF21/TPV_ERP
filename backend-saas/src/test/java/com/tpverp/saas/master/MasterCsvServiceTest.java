package com.tpverp.saas.master;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MasterCsvServiceTest {

    @Test
    void rejectsUnexpectedHeadersBeforeWriting() {
        MasterCsvService service = new MasterCsvService(null, null, null);
        assertThatThrownBy(() -> service.importCsv(java.util.UUID.randomUUID(), "customers",
                "wrong,name\nC1,Cliente\n"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
