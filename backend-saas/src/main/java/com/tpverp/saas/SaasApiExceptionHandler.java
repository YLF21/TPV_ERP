package com.tpverp.saas;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Emits controlled business reasons without exposing stack traces or raw exception bodies. */
@RestControllerAdvice
public class SaasApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception) {
        String reason = exception.getReason();
        String detail = reason == null || reason.isBlank()
                ? "La solicitud SaaS no pudo completarse"
                : reason.trim().substring(0, Math.min(reason.trim().length(), 512));
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), detail);
    }
}
