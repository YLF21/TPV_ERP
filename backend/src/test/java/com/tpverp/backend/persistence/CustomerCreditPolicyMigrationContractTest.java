package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CustomerCreditPolicyMigrationContractTest {

    @Test
    void migrationAddsCreditPolicyRepairsPaidDebtAndRegistersOverridePermission() throws Exception {
        var sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V83__customer_credit_policy.sql"))
                .toLowerCase();

        assertThat(sql).contains(
                "alter table cliente",
                "credit_enabled boolean not null default true",
                "credit_limit numeric(19,2)",
                "payment_term_days integer not null default 30",
                "credit_blocked boolean not null default false",
                "block_on_overdue boolean not null default false");
        assertThat(sql).contains(
                "credit_limit is null or credit_limit >= 0",
                "payment_term_days between 0 and 3650");
        assertThat(sql).contains(
                "'customer_credit_override'",
                "'document.permissions.receivables.creditoverride'",
                "on conflict (codigo) do update");
        assertThat(sql).contains(
                "update documento document",
                "set estado = 'pagado'",
                "document.estado = 'parcial'",
                "sum(payment.importe)");
        assertThat(sql).contains(
                "create index if not exists idx_customer_credit_open_debt",
                "where tipo in ('albaran_venta','factura_venta')",
                "estado in ('pendiente','parcial')");
    }
}
