package com.tpverp.backend.shared.api;

import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.shared.i18n.LocalizedMessages;
import com.tpverp.backend.shared.i18n.RequiredField;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import com.tpverp.backend.shared.i18n.SystemErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final LocalizedMessages messages;

    public ApiExceptionHandler(MessageSource messageSource) {
        this.messages = new LocalizedMessages(messageSource);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ProblemDetail authenticationFailed(
            AuthenticationFailedException exception,
            HttpServletRequest request) {
        return systemProblem(HttpStatus.UNAUTHORIZED, SystemErrorCode.AUTHENTICATION_FAILED, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validationFailed(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = requiredFieldDetail(exception, language)
                .orElseGet(() -> messages.system(SystemErrorCode.VALIDATION_ERROR, language));
        return problem(HttpStatus.BAD_REQUEST, SystemErrorCode.VALIDATION_ERROR.name(), detail, language);
    }

    @ExceptionHandler(LicenseValidationException.class)
    ProblemDetail invalidLicense(
            LicenseValidationException exception,
            HttpServletRequest request) {
        return systemProblem(HttpStatus.UNPROCESSABLE_CONTENT, SystemErrorCode.INVALID_LICENSE, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.BAD_REQUEST,
                SystemErrorCode.VALIDATION_ERROR.name(),
                messages.legacy(exception.getMessage(), language).orElse(exception.getMessage()),
                language);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail stateConflict(
            IllegalStateException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.CONFLICT,
                SystemErrorCode.STATE_CONFLICT.name(),
                messages.legacy(exception.getMessage(), language).orElse(exception.getMessage()),
                language);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return systemProblem(HttpStatus.CONFLICT, SystemErrorCode.DATA_INTEGRITY_CONFLICT, request);
    }

    private ProblemDetail systemProblem(HttpStatus status, SystemErrorCode code, HttpServletRequest request) {
        var language = language(request);
        return problem(status, code.name(), messages.system(code, language), language);
    }

    private Optional<String> requiredFieldDetail(
            MethodArgumentNotValidException exception,
            SupportedLanguage language) {
        return exception.getBindingResult().getFieldErrors().stream()
                .filter(ApiExceptionHandler::isRequiredError)
                .flatMap(error -> RequiredField.from(error.getObjectName(), error.getField()).stream())
                .findFirst()
                .map(field -> messages.required(field, language));
    }

    private static boolean isRequiredError(FieldError error) {
        var codes = error.getCodes();
        if (codes == null) {
            return false;
        }
        return Arrays.stream(codes)
                .anyMatch(code -> code.startsWith("NotBlank")
                        || code.startsWith("NotNull")
                        || code.startsWith("NotEmpty"));
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, SupportedLanguage language) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:tpv-erp:error:" + code));
        problem.setProperty("code", code);
        problem.setProperty("locale", language.localeCode());
        return problem;
    }

    private static SupportedLanguage language(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return user.getIdioma();
        }
        if (request == null) {
            return SupportedLanguage.ES;
        }
        return SupportedLanguage.fromHeader(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }
}
