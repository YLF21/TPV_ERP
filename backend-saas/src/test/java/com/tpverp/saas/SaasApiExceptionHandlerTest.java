package com.tpverp.saas;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.saas.loyalty.MemberBalanceReservationConflictException;
import com.tpverp.saas.loyalty.MemberWalletBootstrapRequiredException;
import org.junit.jupiter.api.Test;

class SaasApiExceptionHandlerTest {

    private final SaasApiExceptionHandler handler = new SaasApiExceptionHandler();

    @Test
    void publicaCodigoTipadoParaReservaRealmenteOcupada() {
        var problem = handler.memberBalanceReservationConflict(
                new MemberBalanceReservationConflictException("ocupada"));

        assertThat(problem.getProperties()).containsEntry(
                "code", "MEMBER_BALANCE_RESERVED_ELSEWHERE");
    }

    @Test
    void publicaCodigoSeparadoCuandoFaltaBootstrap() {
        var problem = handler.memberWalletBootstrapRequired(
                new MemberWalletBootstrapRequiredException("falta bootstrap"));

        assertThat(problem.getProperties()).containsEntry(
                "code", "MEMBER_WALLET_BOOTSTRAP_REQUIRED");
    }
}
