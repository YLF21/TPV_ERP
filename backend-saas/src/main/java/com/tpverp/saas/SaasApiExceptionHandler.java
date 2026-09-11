package com.tpverp.saas;

import com.tpverp.saas.loyalty.MemberBalanceReservationConflictException;
import com.tpverp.saas.loyalty.MemberWalletBootstrapRequiredException;
import com.tpverp.saas.customer.CustomerIdentityException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Emits controlled business reasons without exposing stack traces or raw exception bodies. */
@RestControllerAdvice
public class SaasApiExceptionHandler {

    @ExceptionHandler(CustomerIdentityException.class)
    ProblemDetail customerIdentity(CustomerIdentityException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setProperty("code", exception.getCode());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException exception) {
        // PostgreSQL error detail can contain the document number. Never send it to callers.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            if (message.contains("CUSTOMER_DOCUMENT_DUPLICATE")
                    || message.contains("uq_saas_customer_document_number")) {
                return customerIdentity(CustomerIdentityException.duplicate());
            }
            if (message.contains("CUSTOMER_DOCUMENT_INVALID")) return customerIdentity(CustomerIdentityException.invalid());
            if (message.contains("CUSTOMER_IDENTITY_CONFLICT")
                    || message.contains("uq_saas_customer_pending_identity_operation")) {
                return customerIdentity(CustomerIdentityException.conflict());
            }
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "La escritura entra en conflicto con los datos existentes");
    }

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
