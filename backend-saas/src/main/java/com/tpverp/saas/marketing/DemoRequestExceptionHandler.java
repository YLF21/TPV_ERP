package com.tpverp.saas.marketing;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-100)
@RestControllerAdvice(assignableTypes = {DemoRequestController.class, DemoRequestAdminController.class})
public class DemoRequestExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        // Do not expose or log rejected values: public requests may contain personal data.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "La solicitud contiene campos invalidos");
        problem.setProperty("code", "VALIDATION_FAILED");
        Map<String, String> fields = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException validation) {
            for (var error : validation.getBindingResult().getFieldErrors()) {
                String field = error.getField().equals("productSelectionValid") ? "products" : error.getField();
                if (field.startsWith("products[")) field = "products";
                String code = switch (error.getCode() == null ? "" : error.getCode()) {
                    case "NotBlank", "NotNull" -> "REQUIRED";
                    case "Size" -> "TOO_LONG";
                    case "AssertTrue" -> field.equals("privacyAccepted") ? "MUST_ACCEPT" : "INVALID";
                    default -> "INVALID";
                };
                fields.putIfAbsent(field, code);
            }
        } else if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
            fields.put(mismatch.getName(), "INVALID");
        } else fields.put("request", "INVALID");
        problem.setProperty("fields", fields);
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler(DemoRequestFailure.class)
    ResponseEntity<ProblemDetail> controlledFailure(DemoRequestFailure exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status, exception.getMessage());
        problem.setProperty("code", exception.code);
        if (!exception.fields.isEmpty()) problem.setProperty("fields", exception.fields);
        var response = ResponseEntity.status(exception.status).cacheControl(CacheControl.noStore());
        if (exception.retryAfter != null) response.header("Retry-After", exception.retryAfter.toString());
        return response.body(problem);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable(DataAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "La recepcion de solicitudes no esta disponible temporalmente");
        problem.setProperty("code", "SERVICE_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header("Retry-After", "60").body(problem);
    }
}
