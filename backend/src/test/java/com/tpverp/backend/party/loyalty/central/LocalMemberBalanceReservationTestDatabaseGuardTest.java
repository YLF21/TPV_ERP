package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalMemberBalanceReservationTestDatabaseGuardTest {
    @Test
    void rejectsDevelopmentDatabaseInJdbcUrl() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLegacyJdbcFormWithoutExplicitCatalog() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql:tpv_erp_dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingCatalog() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDevelopmentCatalog() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateDatabaseName(
                "tpv_erp_dev"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCurrentSchemaCaseInsensitively() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_test?CURRENTSCHEMA=public"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOptionsAndSearchPathCaseInsensitively() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_test?OPTIONS=-c%20search_path%3Dpublic"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_test?SeArCh_PaTh=public"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmbeddedCredentialsInQuery() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_test?USER=fixture"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_test?PASSWORD=fixture"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmbeddedCredentialsInAuthority() {
        assertThatThrownBy(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://fixture:fixture@localhost:5432/tpv_erp_test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSafeTestDatabaseUrl() {
        assertThatCode(() -> LocalMemberBalanceReservationTestDatabaseGuard.validateJdbcUrl(
                "jdbc:postgresql://localhost:5432/tpv_erp_test?sslmode=disable"))
                .doesNotThrowAnyException();
    }
}
