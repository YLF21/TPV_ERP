package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV138ContractTest {

    @Test
    void repairsOnlyUnpaidInvoicesBackedByTheFullyPaidSourceTicket()
            throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V138__liquidacion_facturas_desde_ticket_pagado.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase();

            assertThat(sql)
                    .contains("add column liquidado_por_origen boolean not null default false")
                    .contains("set estado = 'pagado'")
                    .contains("liquidado_por_origen = true")
                    .contains("invoice.estado = 'pendiente'")
                    .contains("not exists")
                    .contains("invoice_payment.documento_id = invoice.id")
                    .contains("relation.tipo = 'factura_de'")
                    .contains("source_ticket.tipo = 'ticket'")
                    .contains("source_ticket.estado = 'confirmado'")
                    .contains("source_ticket.numero = invoice.num_ticket")
                    .contains("source_ticket.total = invoice.total")
                    .contains("source_payment.documento_id = source_ticket.id");
        }
    }
}
