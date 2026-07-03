package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationV33ContractTest {

    @Test
    void createsMemberLoyaltyTablesAndFields() throws IOException {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V33__fidelizacion_miembros.sql")) {
            assertThat(stream).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                    .contains("create table member_category")
                    .contains("code varchar(32) not null")
                    .contains("'empleado'")
                    .contains("15.00")
                    .contains("manual_only")
                    .contains("add column member_points")
                    .contains("create table member_settings")
                    .contains("create table member_movement")
                    .contains("create table member_balance_lot")
                    .contains("create table member_balance_lot_consumption")
                    .contains("create table member_card_delivery");
        }
    }
}
