package com.tpverp.backend.shared.api;

import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.application.RoleInUseException;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.PaymentTerminalApiException;
import com.tpverp.backend.inventory.WarehouseConfirmationException;
import com.tpverp.backend.shared.i18n.LocalizedMessages;
import com.tpverp.backend.shared.i18n.RequiredField;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import com.tpverp.backend.shared.i18n.SystemErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final LocalizedMessages messages;

    public ApiExceptionHandler(MessageSource messageSource) {
        this.messages = new LocalizedMessages(messageSource);
    }

    @ExceptionHandler(PaymentTerminalApiException.class)
    ProblemDetail paymentTerminalProblem(PaymentTerminalApiException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), language(request));
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
                localizedExceptionDetail(exception.getMessage(), SystemErrorCode.VALIDATION_ERROR, language),
                language);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(
            NoSuchElementException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = switch (language) {
            case EN -> "Resource not found";
            case ZH -> "未找到资源";
            default -> "Recurso no encontrado";
        };
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", detail, language);
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail stateConflict(
            IllegalStateException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.CONFLICT,
                SystemErrorCode.STATE_CONFLICT.name(),
                localizedExceptionDetail(exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language);
    }

    @ExceptionHandler(RoleInUseException.class)
    ProblemDetail roleInUse(RoleInUseException exception, HttpServletRequest request) {
        var language = language(request);
        long count = exception.assignedUsers();
        var detail = switch (language) {
            case EN -> count == 1
                    ? "The role is assigned to 1 user. Reassign that user before deleting it."
                    : "The role is assigned to %d users. Reassign them before deleting it.".formatted(count);
            case ZH -> "该角色已分配给 %d 个用户。请先重新分配这些用户，然后再删除该角色。".formatted(count);
            default -> count == 1
                    ? "El rol está asignado a 1 usuario. Reasígnalo antes de eliminar el rol."
                    : "El rol está asignado a %d usuarios. Reasígnalos antes de eliminar el rol.".formatted(count);
        };
        var problem = problem(HttpStatus.CONFLICT, "ROLE_IN_USE", detail, language);
        problem.setProperty("assignedUsers", count);
        return problem;
    }

    @ExceptionHandler(WarehouseConfirmationException.class)
    ProblemDetail warehouseConfirmationConflict(
            WarehouseConfirmationException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.CONFLICT,
                SystemErrorCode.STATE_CONFLICT.name(),
                localizedExceptionDetail(exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language);
    }

    private String localizedExceptionDetail(
            String detail,
            SystemErrorCode fallbackCode,
            SupportedLanguage language) {
        return messages.legacy(detail, language).orElseGet(() ->
                language == SupportedLanguage.ES ? detail : messages.system(fallbackCode, language));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return systemProblem(HttpStatus.CONFLICT, SystemErrorCode.DATA_INTEGRITY_CONFLICT, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail methodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        var language = language(request);
        var method = Optional.ofNullable(exception.getMethod()).orElse(request == null ? "" : request.getMethod());
        var path = request == null ? "" : request.getRequestURI();
        var supportedMethods = supportedMethods(exception.getSupportedMethods());
        var detail = methodNotSupportedDetail(method, path, supportedMethods, language);
        var problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", detail, language);
        problem.setProperty("method", method);
        problem.setProperty("path", path);
        problem.setProperty("supportedMethods", supportedMethods);
        return problem;
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

    private static String supportedMethods(String[] methods) {
        if (methods == null || methods.length == 0) {
            return "";
        }
        return String.join(", ", methods);
    }

    private static String methodNotSupportedDetail(
            String method,
            String path,
            String supportedMethods,
            SupportedLanguage language) {
        return switch (language) {
            case EN -> "Method %s is not allowed for %s. Use %s.".formatted(method, path, supportedMethods);
            case ZH -> "%s 不允许对 %s 使用。请使用 %s。".formatted(method, path, supportedMethods);
            default -> "Metodo %s no permitido para %s. Usa %s.".formatted(method, path, supportedMethods);
        };
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
