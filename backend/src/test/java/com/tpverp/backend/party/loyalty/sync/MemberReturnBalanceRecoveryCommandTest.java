package com.tpverp.backend.party.loyalty.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberReturnBalanceRecoveryCommandTest {

    @Test
    void validaEscalaMonetariaExacta() {
        assertThatThrownBy(() -> command(UUID.randomUUID(), new BigDecimal("0.221"),
                List.of(), "fingerprint"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void validaQueClaimsSumaElImporteAtribuido() {
        var source = UUID.randomUUID();
        var claim = claim(source, new BigDecimal("0.22"));
        assertThatThrownBy(() -> command(source, new BigDecimal("0.23"), List.of(claim),
                fingerprint(source, new BigDecimal("0.23"), List.of(claim))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claims debe igualar");
    }

    @Test
    void rechazaLotIdDuplicado() {
        var source = UUID.randomUUID();
        var lotId = UUID.randomUUID();
        var first = new MemberBalanceCentralGateway.RetentionClaim(
                lotId, UUID.randomUUID(), source, new BigDecimal("1.00"), new BigDecimal("0.11"));
        var second = new MemberBalanceCentralGateway.RetentionClaim(
                lotId, UUID.randomUUID(), source, new BigDecimal("1.00"), new BigDecimal("0.11"));
        assertThatThrownBy(() -> command(source, new BigDecimal("0.22"), List.of(first, second),
                fingerprint(source, new BigDecimal("0.22"), List.of(first, second))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lotId");
    }

    @Test
    void rechazaClaimDeOtroDocumentoOrigen() {
        var source = UUID.randomUUID();
        var claim = claim(UUID.randomUUID(), new BigDecimal("0.22"));
        assertThatThrownBy(() -> command(source, new BigDecimal("0.22"), List.of(claim),
                fingerprint(source, new BigDecimal("0.22"), List.of(claim))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceDocumentId");
    }

    @Test
    void rechazaFingerprintQueNoCorrespondeAlSnapshot() {
        var source = UUID.randomUUID();
        var claim = claim(source, new BigDecimal("0.22"));
        assertThatThrownBy(() -> command(source, new BigDecimal("0.22"), List.of(claim), "incorrecto"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claimsFingerprint");
    }

    @Test
    void conservaLaIdentidadCompletaDeLaReserva() {
        var source = UUID.randomUUID();
        var claim = claim(source, new BigDecimal("0.22"));
        UUID reservationId = UUID.randomUUID();
        var command = command(source, new BigDecimal("0.22"), List.of(claim),
                fingerprint(source, new BigDecimal("0.22"), List.of(claim)),
                new MemberReturnBalanceRecoveryCommand.ReservationIdentity(
                        reservationId, "sale-operation"));

        assertThat(command.reservation().centralReservationId()).isEqualTo(reservationId);
        assertThat(command.reservation().saleId()).isEqualTo("sale-operation");
        assertThat(command.claims()).containsExactly(claim);
    }

    private static MemberReturnBalanceRecoveryCommand command(
            UUID source,
            BigDecimal amount,
            List<MemberBalanceCentralGateway.RetentionClaim> claims,
            String fingerprint) {
        return command(source, amount, claims, fingerprint, null);
    }

    private static MemberReturnBalanceRecoveryCommand command(
            UUID source,
            BigDecimal amount,
            List<MemberBalanceCentralGateway.RetentionClaim> claims,
            String fingerprint,
            MemberReturnBalanceRecoveryCommand.ReservationIdentity reservation) {
        return new MemberReturnBalanceRecoveryCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), source, UUID.randomUUID(), amount, fingerprint,
                claims, reservation);
    }

    private static MemberBalanceCentralGateway.RetentionClaim claim(
            UUID source, BigDecimal amount) {
        return new MemberBalanceCentralGateway.RetentionClaim(
                UUID.randomUUID(), UUID.randomUUID(), source,
                new BigDecimal("1.00"), amount);
    }

    private static String fingerprint(
            UUID source, BigDecimal amount,
            List<MemberBalanceCentralGateway.RetentionClaim> claims) {
        return MemberReturnBalanceRetentionPlanner.fingerprint(source, amount, claims);
    }
}
