package com.tpverp.saas;

import com.tpverp.saas.loyalty.MemberBalanceReservationConflictException;
import com.tpverp.saas.loyalty.MemberWalletBootstrapRequiredException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Emits controlled business reasons without exposing stack traces or raw exception bodies. */
@RestControllerAdvice
public class SaasApiExceptionHandler {

    @ExceptionHandler(MemberBalanceReservationConflictException.class)
    ProblemDetail memberBalanceReservationConflict(MemberBalanceReservationConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatusCode(), exception.getReason());
        problem.setProperty("code", "MEMBER_BALANCE_RESERVED_ELSEWHERE");
        return problem;
    }

    @ExceptionHandler(MemberWalletBootstrapRequiredException.class)
    ProblemDetail memberWalletBootstrapRequired(MemberWalletBootstrapRequiredException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                exception.getStatusCode(), exception.getReason());
        problem.setProperty("code", "MEMBER_WALLET_BOOTSTRAP_REQUIRED");
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception) {
        String reason = exception.getReason();
        String detail = reason == null || reason.isBlank()
                ? "La solicitud SaaS no pudo completarse"
                : reason.trim().substring(0, Math.min(reason.trim().length(), 512));
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), detail);
    }
}
