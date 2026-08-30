package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.verifactu.FiscalSubmissionEvidence;
import com.tpverp.backend.verifactu.FiscalSubmissionResponseEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MigrationV229FiscalEvidenceContractTest {

    @Test
    void creaEvidenciaSeparadaAppendOnlyYNoMigraPayloadHistorico() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V229__evidencia_inmutable_envio_fiscal.sql"),
                StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);

        assertThat(sql).contains("create table evidencia_envio_fiscal")
                .contains("create table respuesta_evidencia_envio_fiscal")
                .contains("request_sha256")
                .contains("response_sha256")
                .contains("check (octet_length(request_xml) between 1 and 67108864)")
                .contains("check (octet_length(response_payload) between 0 and 10485760)")
                .contains("empresa_id")
                .contains("instalacion_id")
                .contains("batch_owner")
                .contains("request_preparado_en")
                .contains("unique (empresa_id, instalacion_id, entorno, batch_owner)")
                .contains("before update or delete on evidencia_envio_fiscal")
                .contains("before update or delete on respuesta_evidencia_envio_fiscal")
                .contains("add column if not exists evidencia_id")
                .contains("check (evidencia_id is null or (xml_enviado is null and respuesta is null))")
                .doesNotContain("update intento_envio_fiscal")
                .doesNotContain("delete from intento_envio_fiscal");

        assertThat(FiscalSubmissionEvidence.MAX_REQUEST_BYTES).isEqualTo(67108864);
        assertThat(FiscalSubmissionResponseEvidence.MAX_RESPONSE_BYTES).isEqualTo(10485760);
    }
}
