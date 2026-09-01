package com.tpverp.backend.shared.api;

import com.tpverp.backend.catalog.ProductClassificationVersionConflictException;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.document.CustomerCreditLimitExceededException;
import com.tpverp.backend.document.FiscalQrUnavailableException;
import com.tpverp.backend.document.GenericSaleConfirmationBlockedException;
import com.tpverp.backend.document.PaymentSessionClosedException;
import com.tpverp.backend.document.RefundTenderOverrideRequiredException;
import com.tpverp.backend.document.TicketHasPreviousReturnsException;
import com.tpverp.backend.document.TicketAlreadyInvoicedException;
import com.tpverp.backend.document.TicketGeneratedVoucherAlreadyUsedException;
import com.tpverp.backend.document.TicketNotFoundException;
import com.tpverp.backend.document.template.DocumentTemplateRequiredException;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.application.RoleInUseException;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.SaleOperationAuthorizationDeniedException;
import com.tpverp.backend.security.sales.SaleOperationAuthorizationThrottledException;
import com.tpverp.backend.terminal.PaymentTerminalApiException;
import com.tpverp.backend.inventory.WarehouseConfirmationException;
import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralException;
import com.tpverp.backend.party.loyalty.central.MemberBalanceReservationConflictException;
import com.tpverp.backend.party.MemberBalanceOfficialSyncRequiredException;
import com.tpverp.backend.verifactu.VerifactuCertificateApiException;
import com.tpverp.backend.verifactu.FiscalProductCapabilityViolationException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final LocalizedMessages messages;

    public ApiExceptionHandler(MessageSource messageSource) {
        this.messages = new LocalizedMessages(messageSource);
    }

    @ExceptionHandler(PaymentTerminalApiException.class)
    ProblemDetail paymentTerminalProblem(PaymentTerminalApiException exception, HttpServletRequest request) {
        return problem(exception.status(), exception.code(), exception.getMessage(), language(request), request);
    }

    @ExceptionHandler(MemberBalanceCentralException.class)
    ProblemDetail memberBalanceCentralProblem(
            MemberBalanceCentralException exception,
            HttpServletRequest request) {
        var language = language(request);
        HttpStatus status = switch (exception.getKind()) {
            case CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case UNAVAILABLE, UNAUTHORIZED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = "MEMBER_BALANCE_CENTRAL_" + exception.getKind().name();
        String detail = switch (language) {
            case EN -> switch (exception.getKind()) {
                case CONFLICT -> "The member balance is reserved at another checkout";
                case REJECTED -> "The member balance operation was rejected";
                default -> "The central member balance service is unavailable";
            };
            case ZH -> switch (exception.getKind()) {
                case CONFLICT -> "会员余额已在其他收银台预留";
                case REJECTED -> "会员余额操作被拒绝";
                default -> "中央会员余额服务当前不可用";
            };
            default -> switch (exception.getKind()) {
                case CONFLICT -> "El saldo del miembro esta reservado en otra caja";
                case REJECTED -> "La operacion de saldo del miembro ha sido rechazada";
                default -> "El servicio central de saldo del miembro no esta disponible";
            };
        };
        var problem = problem(status, code, detail, language, request);
        problem.setProperty("loyaltyFailure", exception.getKind().name());
        if (exception.getStatusCode() != null) {
            problem.setProperty("centralStatus", exception.getStatusCode());
        }
        return problem;
    }

    @ExceptionHandler(VerifactuCertificateApiException.class)
    ProblemDetail verifactuCertificateProblem(
            VerifactuCertificateApiException exception,
            HttpServletRequest request) {
        var language = language(request);
        var fallback = exception.status().is4xxClientError()
                && exception.status() != HttpStatus.BAD_REQUEST
                ? SystemErrorCode.STATE_CONFLICT
                : SystemErrorCode.VALIDATION_ERROR;
        var problem = problem(
                exception.status(), exception.code(),
                localizedExceptionDetail(exception.getMessage(), fallback, language), language, request);
        exception.properties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ProblemDetail authenticationFailed(
            AuthenticationFailedException exception,
            HttpServletRequest request) {
        return systemProblem(HttpStatus.UNAUTHORIZED, SystemErrorCode.AUTHENTICATION_FAILED, request);
    }

    @ExceptionHandler(SaleOperationAuthorizationDeniedException.class)
    ProblemDetail saleOperationAuthorizationDenied(
            SaleOperationAuthorizationDeniedException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.FORBIDDEN,
                SaleOperationAuthorizationDeniedException.CODE,
                saleAuthorizationDeniedDetail(language),
                language,
                request);
    }

    @ExceptionHandler(SaleOperationAuthorizationThrottledException.class)
    ProblemDetail saleOperationAuthorizationThrottled(
            SaleOperationAuthorizationThrottledException exception,
            HttpServletRequest request) {
        var language = language(request);
        var problem = problem(
                HttpStatus.TOO_MANY_REQUESTS,
                SaleOperationAuthorizationThrottledException.CODE,
                saleAuthorizationThrottledDetail(language),
                language,
                request);
        problem.setProperty("blockedUntil", exception.blockedUntil().toString());
        problem.setProperty("retryAfterSeconds", exception.retryAfterSeconds());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validationFailed(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = requiredFieldDetail(exception, language)
                .orElseGet(() -> messages.system(SystemErrorCode.VALIDATION_ERROR, language));
        return problem(HttpStatus.BAD_REQUEST, SystemErrorCode.VALIDATION_ERROR.name(), detail, language, request);
    }

    @ExceptionHandler(LicenseValidationException.class)
    ProblemDetail invalidLicense(
            LicenseValidationException exception,
            HttpServletRequest request) {
        return systemProblem(HttpStatus.UNPROCESSABLE_CONTENT, SystemErrorCode.INVALID_LICENSE, request);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    ProblemDetail ticketNotFound(
            TicketNotFoundException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.NOT_FOUND,
                TicketNotFoundException.CODE,
                localizedExceptionDetail(
                        exception.getMessage(), SystemErrorCode.VALIDATION_ERROR, language),
                language,
                request);
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
                language,
                request);
    }

    @ExceptionHandler(RefundTenderOverrideRequiredException.class)
    ProblemDetail refundTenderOverrideRequired(
            RefundTenderOverrideRequiredException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = switch (language) {
            case EN -> "The selected refund method differs from the original payment method and requires authorization";
            case ZH -> "所选退款方式与原付款方式不同，需要授权";
            default -> "La forma de devolución no coincide con el pago original y requiere autorización";
        };
        return problem(
                HttpStatus.CONFLICT,
                RefundTenderOverrideRequiredException.CODE,
                detail,
                language,
                request);
    }

    @ExceptionHandler(TicketHasPreviousReturnsException.class)
    ProblemDetail ticketHasPreviousReturns(
            TicketHasPreviousReturnsException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.CONFLICT,
                TicketHasPreviousReturnsException.CODE,
                localizedExceptionDetail(
                        exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
    }

    @ExceptionHandler(TicketAlreadyInvoicedException.class)
    ProblemDetail ticketAlreadyInvoiced(
            TicketAlreadyInvoicedException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.CONFLICT,
                TicketAlreadyInvoicedException.CODE,
                localizedExceptionDetail(
                        exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
    }

    @ExceptionHandler(TicketGeneratedVoucherAlreadyUsedException.class)
    ProblemDetail ticketGeneratedVoucherAlreadyUsed(
            TicketGeneratedVoucherAlreadyUsedException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = switch (language) {
            case EN -> "This ticket cannot be cancelled because it generated a voucher that has already been used.";
            case ZH -> "无法作废此小票，因为它生成的代金券已被使用。";
            default -> "No se puede anular este ticket porque generó un vale que ya se ha utilizado.";
        };
        return problem(
                HttpStatus.CONFLICT,
                TicketGeneratedVoucherAlreadyUsedException.CODE,
                detail,
                language,
                request);
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
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", detail, language, request);
    }

    @ExceptionHandler(DocumentTemplateRequiredException.class)
    ProblemDetail documentTemplateRequired(
            DocumentTemplateRequiredException exception,
            HttpServletRequest request) {
        var language = language(request);
        var problem = problem(
                HttpStatus.CONFLICT,
                DocumentTemplateRequiredException.CODE,
                localizedExceptionDetail(
                        exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
        problem.setProperty("documentType", exception.documentType().name());
        problem.setProperty("format", exception.format().name());
        return problem;
    }

    @ExceptionHandler(MemberBalanceReservationConflictException.class)
    ProblemDetail memberBalanceReservationConflictProblem(
            MemberBalanceReservationConflictException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "MEMBER_BALANCE_RESERVED_ELSEWHERE",
                exception.getMessage(), language(request), request);
    }

    @ExceptionHandler(MemberBalanceOfficialSyncRequiredException.class)
    ProblemDetail memberBalanceOfficialSyncRequired(
            MemberBalanceOfficialSyncRequiredException exception,
            HttpServletRequest request) {
        var language = language(request);
        var problem = problem(
                HttpStatus.CONFLICT,
                SystemErrorCode.STATE_CONFLICT.name(),
                localizedExceptionDetail(exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
        // Keep the established HTTP code while preserving the precise audit cause.
        ApiExceptionContext.record(
                request,
                MemberBalanceOfficialSyncRequiredException.CODE,
                ApiExceptionContext.API_EXCEPTION_HANDLER_STAGE);
        return problem;
    }

    @ExceptionHandler(FiscalProductCapabilityViolationException.class)
    ProblemDetail fiscalProductCapabilityViolation(
            FiscalProductCapabilityViolationException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT,
                FiscalProductCapabilityViolationException.CODE,
                exception.getMessage(), language(request), request);
    }

    @ExceptionHandler(FiscalQrUnavailableException.class)
    ProblemDetail fiscalQrUnavailable(
            FiscalQrUnavailableException exception,
            HttpServletRequest request) {
        var language = language(request);
        var problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                FiscalQrUnavailableException.CODE,
                localizedExceptionDetail(
                        exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
        problem.setProperty("documentId", exception.documentId().toString());
        problem.setProperty("fiscalQrFailure", exception.reason().name());
        problem.setProperty("retryable", exception.retryable());
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail stateConflict(
            IllegalStateException exception,
            HttpServletRequest request) {
        var language = language(request);
        var traceId = CorrelationIdFilter.getOrCreate(request);
        LOGGER.warn(
                "State conflict traceId={} method={} path={} reason={}",
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                safeStateConflictReason(exception.getMessage()));
        return problem(
                HttpStatus.CONFLICT,
                SystemErrorCode.STATE_CONFLICT.name(),
                localizedExceptionDetail(exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
    }

    @ExceptionHandler(ProductClassificationVersionConflictException.class)
    ProblemDetail productClassificationVersionConflict(
            ProductClassificationVersionConflictException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = messages.system(SystemErrorCode.PRODUCT_VERSION_CONFLICT, language);
        var response = problem(
                HttpStatus.CONFLICT, SystemErrorCode.PRODUCT_VERSION_CONFLICT.name(), detail, language, request);
        response.setProperty("action", "RELOAD_PRODUCTS");
        response.setProperty("retryable", true);
        response.setProperty("conflicts", exception.conflicts());
        return response;
    }

    @ExceptionHandler(PaymentSessionClosedException.class)
    ProblemDetail paymentSessionClosed(
            PaymentSessionClosedException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = switch (language) {
            case EN -> exception.retryable()
                    ? "The previous payment session was cancelled. A new checkout is required."
                    : "The payment session has already been finalized.";
            case ZH -> exception.retryable()
                    ? "上一个付款会话已取消，需要创建新的结账会话。"
                    : "付款会话已完成。";
            default -> exception.retryable()
                    ? "La sesión de cobro anterior fue cancelada. Es necesario iniciar un cobro nuevo."
                    : "La sesión de cobro ya fue finalizada.";
        };
        var problem = problem(
                HttpStatus.CONFLICT,
                PaymentSessionClosedException.CODE,
                detail,
                language,
                request);
        problem.setProperty("paymentSessionStatus", exception.status().name());
        problem.setProperty("retryable", exception.retryable());
        return problem;
    }

    @ExceptionHandler(CustomerCreditLimitExceededException.class)
    ProblemDetail customerCreditLimitExceeded(
            CustomerCreditLimitExceededException exception,
            HttpServletRequest request) {
        var language = language(request);
        return problem(
                HttpStatus.CONFLICT,
                CustomerCreditLimitExceededException.CODE,
                localizedExceptionDetail(exception.getMessage(), SystemErrorCode.STATE_CONFLICT, language),
                language,
                request);
    }

    @ExceptionHandler(GenericSaleConfirmationBlockedException.class)
    ProblemDetail genericSaleConfirmationBlocked(
            GenericSaleConfirmationBlockedException exception,
            HttpServletRequest request) {
        var language = language(request);
        var detail = exception.reason()
                == GenericSaleConfirmationBlockedException.Reason.MISMATCH
                ? switch (language) {
                    case EN -> "The sales draft changed after it was authorized. Recreate it before confirming.";
                    case ZH -> "销售草稿在授权后已更改。请重新创建后再确认。";
                    default -> "El borrador de venta cambió después de autorizarse. Vuelva a crearlo antes de confirmarlo.";
                }
                : switch (language) {
                    case EN -> "The sales draft has no persisted authorization manifest. Recreate it before confirming.";
                    case ZH -> "销售草稿没有持久化的授权清单。请重新创建后再确认。";
                    default -> "El borrador de venta no tiene un manifiesto de autorización persistido. Vuelva a crearlo antes de confirmarlo.";
                };
        return problem(
                HttpStatus.CONFLICT,
                exception.code(),
                detail,
                language,
                request);
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
        var problem = problem(HttpStatus.CONFLICT, "ROLE_IN_USE", detail, language, request);
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
                language,
                request);
    }

    private String localizedExceptionDetail(
            String detail,
            SystemErrorCode fallbackCode,
            SupportedLanguage language) {
        return messages.legacy(detail, language).orElseGet(() ->
                messages.system(fallbackCode, language));
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
        var supportedMethods = supportedMethods(exception.getSupportedMethods());
        var detail = safeMethodNotSupportedDetail(method, supportedMethods, language);
        var problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", detail, language, request);
        problem.setProperty("method", method);
        problem.setProperty("supportedMethods", supportedMethods);
        return problem;
    }

    private ProblemDetail systemProblem(HttpStatus status, SystemErrorCode code, HttpServletRequest request) {
        var language = language(request);
        return problem(status, code.name(), messages.system(code, language), language, request);
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

    private ProblemDetail problem(HttpStatus status, String code, String detail, SupportedLanguage language,
            HttpServletRequest request) {
        var stableCode = ApiExceptionContext.normalizeCode(code);
        ApiExceptionContext.record(request, stableCode, ApiExceptionContext.API_EXCEPTION_HANDLER_STAGE);
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:tpv-erp:error:" + stableCode));
        problem.setProperty("code", stableCode);
        problem.setProperty("locale", language.localeCode());
        problem.setProperty("traceId", CorrelationIdFilter.getOrCreate(request));
        return problem;
    }

    private static String supportedMethods(String[] methods) {
        if (methods == null || methods.length == 0) {
            return "";
        }
        return String.join(", ", methods);
    }

    private static String safeMethodNotSupportedDetail(
            String method, String supportedMethods, SupportedLanguage language) {
        return switch (language) {
            case EN -> "Method %s is not allowed. Use %s.".formatted(method, supportedMethods);
            case ZH -> "不允许使用 %s 方法。请使用 %s。".formatted(method, supportedMethods);
            default -> "Método %s no permitido. Usa %s.".formatted(method, supportedMethods);
        };
    }

    private static String safeStateConflictReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unspecified";
        }
        var normalized = reason.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.matches("[A-Za-z0-9_.:-]{1,256}")
                ? normalized
                : "localized_or_dynamic_reason";
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

    private static String saleAuthorizationDeniedDetail(
            SupportedLanguage language) {
        return switch (language) {
            case EN -> "Operational authorization was denied";
            case ZH -> "操作授权已被拒绝";
            default -> "La autorización operativa ha sido rechazada";
        };
    }

    private static String saleAuthorizationThrottledDetail(
            SupportedLanguage language) {
        return switch (language) {
            case EN -> "Too many authorization attempts. Try again later";
            case ZH -> "授权尝试次数过多，请稍后重试";
            default -> "Demasiados intentos de autorización. Inténtalo de nuevo más tarde";
        };
    }
}
