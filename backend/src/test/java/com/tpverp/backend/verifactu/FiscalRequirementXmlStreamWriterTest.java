package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FiscalRequirementXmlStreamWriterTest {
    private static final String SIGNED_ALTA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sf:RegistroAlta xmlns:sf="https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd" xmlns:ds="http://www.w3.org/2000/09/xmldsig#"><sf:IDVersion>1.0</sf:IDVersion><sf:Huella>ABC</sf:Huella><ds:Signature/></sf:RegistroAlta>
            """;

    @Test
    void escribeElSobreOficialYConservaElRegistroFirmadoSinListarTodosLosRegistros() throws Exception {
        Path output = Files.createTempFile("required", ".xml");
        try {
            try (var writer = new FiscalRequirementXmlStreamWriter(output, "Empresa Ñ & Hijos",
                    "B12345678", new FiscalRequirementContext("REQ-2026-001", true))) {
                writer.appendSignedRecord(SIGNED_ALTA);
                assertThat(writer.records()).isEqualTo(1);
            }
            var xml = Files.readString(output);
            assertThat(xml).contains("<sf:RemisionRequerimiento>")
                    .contains("<sf:RefRequerimiento>REQ-2026-001</sf:RefRequerimiento>")
                    .contains("<sf:FinRequerimiento>S</sf:FinRequerimiento>")
                    .contains("<sf:NombreRazon>Empresa Ñ &amp; Hijos</sf:NombreRazon>")
                    .contains("<sf:RegistroAlta")
                    .doesNotContain("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n            <?xml");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void rechazaRegistrosNoFirmadosOMezcladosConOtroNodo() throws Exception {
        Path output = Files.createTempFile("required", ".xml");
        try (var writer = new FiscalRequirementXmlStreamWriter(output, "Empresa", "B12345678",
                new FiscalRequirementContext("REQ", true))) {
            assertThatThrownBy(() -> writer.appendSignedRecord("<sf:RegistroAlta xmlns:sf=\""
                    + "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd"
                    + "\"/>"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no contiene firma");
            assertThatThrownBy(() -> writer.appendSignedRecord("<sf:RegistroAlta xmlns:sf=\"x\"/>"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer.appendSignedRecord("<sf:Otro xmlns:sf=\""
                    + "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd"
                    + "\"/>"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void rechazaUnXmlFirmadoCuyoByteCongeladoHaCambiad() throws Exception {
        var hash = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SIGNED_ALTA.getBytes(StandardCharsets.UTF_8)));
        FiscalExportJobService.verifyRequiredArtifactHash(SIGNED_ALTA, hash);
        assertThatThrownBy(() -> FiscalExportJobService.verifyRequiredArtifactHash(
                SIGNED_ALTA.replace("ABC", "ABD"), hash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fiscal_required_submission_artifact_hash_mismatch");
    }

    @Test
    void limitaCadaSobreA1000YMarcaSoloElUltimoComoFinal() throws Exception {
        Path first = Files.createTempFile("required-first", ".xml");
        Path last = Files.createTempFile("required-last", ".xml");
        try {
            try (var writer = new FiscalRequirementXmlStreamWriter(first, "Empresa",
                    "B12345678", new FiscalRequirementContext("REQ", false))) {
                for (int i = 0; i < 1_000; i++) writer.appendSignedRecord(SIGNED_ALTA);
                assertThatThrownBy(() -> writer.appendSignedRecord(SIGNED_ALTA))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("fiscal_required_submission_batch_limit");
            }
            try (var writer = new FiscalRequirementXmlStreamWriter(last, "Empresa Ñ",
                    "B12345678", new FiscalRequirementContext("REQ", false))) {
                writer.appendSignedRecord(SIGNED_ALTA);
                writer.markFinished();
            }
            var firstXml = Files.readString(first);
            var lastXml = Files.readString(last);
            assertThat(firstXml).contains("<sf:FinRequerimiento>N</sf:FinRequerimiento>")
                    .contains("<sfLR:RegistroFactura>");
            assertThat(lastXml).contains("<sf:FinRequerimiento>S</sf:FinRequerimiento>");
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(last);
        }
    }

    @Test
    void rechazaMasDeUnElementoRaizAunqueLaFirmaAparezcaDespues() throws Exception {
        Path output = Files.createTempFile("required", ".xml");
        try (var writer = new FiscalRequirementXmlStreamWriter(output, "Empresa", "B12345678",
                new FiscalRequirementContext("REQ", true))) {
            assertThatThrownBy(() -> writer.appendSignedRecord(
                    "<sf:RegistroAlta xmlns:sf=\"" +
                    "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd\"" +
                    "><ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"/></sf:RegistroAlta>"
                    + "<sf:RegistroAnulacion xmlns:sf=\"https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd\"/>"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
