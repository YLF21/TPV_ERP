package com.tpverp.backend.shared.api;

import com.tpverp.backend.document.CustomerCreditLimitExceededException;
import com.tpverp.backend.document.FiscalQrUnavailableException;
import com.tpverp.backend.document.GenericSaleConfirmationBlockedException;
import com.tpverp.backend.document.PaymentSessionClosedException;
import com.tpverp.backend.document.SalePaymentSessionStatus;
import com.tpverp.backend.document.TicketHasPreviousReturnsException;
import com.tpverp.backend.document.TicketAlreadyInvoicedException;
import com.tpverp.backend.document.TicketGeneratedVoucherAlreadyUsedException;
import com.tpverp.backend.document.TicketNotFoundException;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateRequiredException;
import com.tpverp.backend.document.template.DocumentTemplateType;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.application.RoleInUseException;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.SaleOperationAuthorizationDeniedException;
import com.tpverp.backend.security.sales.SaleOperationAuthorizationThrottledException;
import com.tpverp.backend.shared.i18n.LocalizedMessages;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(messageSource());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void translatesSystemErrorsUsingAcceptLanguage() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.authenticationFailed(new AuthenticationFailedException(), request);

        assertEquals("AUTHENTICATION_FAILED", problem.getProperties().get("code"));
        assertEquals("en", problem.getProperties().get("locale"));
        assertEquals("Incorrect username or password", problem.getDetail());
    }

    @Test
    void authenticatedUserLanguageOverridesAcceptLanguage() {
        var user = userWithLanguage(SupportedLanguage.ZH);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.authenticationFailed(new AuthenticationFailedException(), request);

        assertEquals("zh", problem.getProperties().get("locale"));
        assertEquals("用户名或密码不正确", problem.getDetail());
    }

    @Test
    void legacyDirectExceptionMessagesKeepSpanishLocaleUntilMigrated() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.invalidArgument(new IllegalArgumentException("El valor es obligatorio"), request);

        assertEquals("en", problem.getProperties().get("locale"));
        assertEquals("Value is required", problem.getDetail());
    }

    @Test
    void localizesCashWithdrawalExceedingAvailableCash() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es");

        var problem = handler.invalidArgument(
                new IllegalArgumentException("message.cash.withdrawal_exceeds_available_cash"),
                request);

        assertEquals(400, problem.getStatus());
        assertEquals("La retirada supera el efectivo disponible en caja", problem.getDetail());
    }

    @Test
    void reportsMissingTicketsWithAStableNotFoundCodeAndClearMessage() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es");

        var problem = handler.ticketNotFound(new TicketNotFoundException(), request);

        assertEquals(404, problem.getStatus());
        assertEquals("TICKET_NOT_FOUND", problem.getProperties().get("code"));
        assertEquals("Ticket no encontrado", problem.getDetail());
    }

    @Test
    void translatesLegacyBusinessMessagesWhenKnown() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.stateConflict(
                new IllegalStateException("No se puede eliminar un producto con historial"), request);

        assertEquals("en", problem.getProperties().get("locale"));
        assertEquals("A product with history cannot be deleted", problem.getDetail());
    }

    @Test
    void reportsTheExactMissingJrxmlTypeAndFormat() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.documentTemplateRequired(
                new DocumentTemplateRequiredException(
                        DocumentTemplateType.FACTURA_VENTA,
                        DocumentTemplateFormat.TICKET_80),
                request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                DocumentTemplateRequiredException.CODE,
                problem.getProperties().get("code"));
        assertEquals("FACTURA_VENTA", problem.getProperties().get("documentType"));
        assertEquals("TICKET_80", problem.getProperties().get("format"));
        assertEquals(
                "Falta una plantilla JRXML activa para este tipo y formato de documento. "
                        + "Cárgala y actívala en APP GESTIÓN, Configuración, Plantillas de documentos.",
                problem.getDetail());
    }

    @Test
    void translatesExplicitInternalEanConfigurationError() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.stateConflict(
                new IllegalStateException("internal_ean_configuration_required"), request);

        assertEquals(409, problem.getStatus());
        assertEquals("STATE_CONFLICT", problem.getProperties().get("code"));
        assertEquals(
                "Configura primero el código interno de empresa en APP GESTIÓN.",
                problem.getDetail());
    }

    @Test
    void reportsAssignedUserCountWhenRoleCannotBeDeleted() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.roleInUse(new RoleInUseException(3), request);

        assertEquals(409, problem.getStatus());
        assertEquals("ROLE_IN_USE", problem.getProperties().get("code"));
        assertEquals(3L, problem.getProperties().get("assignedUsers"));
        assertEquals(
                "The role is assigned to 3 users. Reassign them before deleting it.",
                problem.getDetail());
    }

    @Test
    void reportsServerProvisioningPreconditionsAsLocalizedConflict() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.stateConflict(
                new IllegalStateException("message.terminal.server_provision_requires_single_store"),
                request);

        assertEquals(409, problem.getStatus());
        assertEquals("STATE_CONFLICT", problem.getProperties().get("code"));
        assertEquals(
                "Debe existir exactamente una tienda antes de configurar el terminal servidor",
                problem.getDetail());
    }

    @Test
    void reportsCustomerCreditLimitWithStableMachineReadableCode() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.customerCreditLimitExceeded(
                new CustomerCreditLimitExceededException(), request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                CustomerCreditLimitExceededException.CODE,
                problem.getProperties().get("code"));
        assertEquals("The operation exceeds the customer credit limit", problem.getDetail());
    }

    @Test
    void reportsPreviousReturnsWithStableMachineReadableCode() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.ticketHasPreviousReturns(
                new TicketHasPreviousReturnsException(), request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                TicketHasPreviousReturnsException.CODE,
                problem.getProperties().get("code"));
        assertEquals(
                "Este ticket ya tiene devoluciones parciales y no puede anularse completo. "
                        + "Utiliza F10 para devolver los artículos restantes.",
                problem.getDetail());
    }

    @Test
    void reportsAlreadyInvoicedTicketWithStableMachineReadableCode() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.ticketAlreadyInvoiced(
                new TicketAlreadyInvoicedException(), request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                TicketAlreadyInvoicedException.CODE,
                problem.getProperties().get("code"));
        assertEquals("Este ticket ya está facturado", problem.getDetail());
    }

    @Test
    void reportsUsedGeneratedVoucherWithoutOperationalDetails() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.ticketGeneratedVoucherAlreadyUsed(
                new TicketGeneratedVoucherAlreadyUsedException(),
                request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                TicketGeneratedVoucherAlreadyUsedException.CODE,
                problem.getProperties().get("code"));
        assertEquals(
                "No se puede anular este ticket porque generó un vale que ya se ha utilizado.",
                problem.getDetail());
        assertEquals(false, problem.getProperties().containsKey("voucherCodes"));
        assertEquals(false, problem.getProperties().containsKey("dependentTicketNumbers"));
    }

    @Test
    void reportsBlockedGenericSaleConfirmationWithStableMachineReadableCode() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.genericSaleConfirmationBlocked(
                new GenericSaleConfirmationBlockedException(), request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                GenericSaleConfirmationBlockedException.CODE,
                problem.getProperties().get("code"));
        assertEquals(
                "The sales draft has no persisted authorization manifest. Recreate it before confirming.",
                problem.getDetail());
    }

    @Test
    void reportsChangedAuthorizationManifestWithDifferentStableCode() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.genericSaleConfirmationBlocked(
                GenericSaleConfirmationBlockedException.mismatch(), request);

        assertEquals(409, problem.getStatus());
        assertEquals(
                GenericSaleConfirmationBlockedException.MISMATCH_CODE,
                problem.getProperties().get("code"));
        assertEquals(
                "The sales draft changed after it was authorized. Recreate it before confirming.",
                problem.getDetail());
    }

    @Test
    void reportsDeniedOperationalAuthorizationWithoutLeakingCredentialDetails() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.saleOperationAuthorizationDenied(
                new SaleOperationAuthorizationDeniedException(
                        new IllegalArgumentException("internal credential detail")),
                request);

        assertEquals(403, problem.getStatus());
        assertEquals(
                SaleOperationAuthorizationDeniedException.CODE,
                problem.getProperties().get("code"));
        assertEquals("Operational authorization was denied", problem.getDetail());
        assertEquals(false, problem.getDetail().contains("credential"));
    }

    @Test
    void reportsOperationalAuthorizationCooldownWithStableRetryMetadata() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");
        var blockedUntil = Instant.parse("2026-07-31T10:00:05Z");

        var problem = handler.saleOperationAuthorizationThrottled(
                new SaleOperationAuthorizationThrottledException(
                        blockedUntil, 5),
                request);

        assertEquals(429, problem.getStatus());
        assertEquals(
                SaleOperationAuthorizationThrottledException.CODE,
                problem.getProperties().get("code"));
        assertEquals(5L, problem.getProperties().get("retryAfterSeconds"));
        assertEquals(blockedUntil.toString(), problem.getProperties().get("blockedUntil"));
    }

    @Test
    void unknownLegacyMessagesNeverLeakSpanishToOtherLanguages() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.invalidArgument(
                new IllegalArgumentException("forms[0] requiere una condicion de proveedor"),
                request);

        assertEquals("en", problem.getProperties().get("locale"));
        assertEquals("The request contains invalid data", problem.getDetail());
    }

    @Test
    void internalBusinessKeysNeverLeakForSpanishUsers() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.stateConflict(
                new IllegalStateException("authoritative_quote_line_total_mismatch"), request);

        assertEquals("STATE_CONFLICT", problem.getProperties().get("code"));
        assertEquals("La operación no es compatible con el estado actual", problem.getDetail());
        assertEquals(36, String.valueOf(problem.getProperties().get("traceId")).length());
    }

    @Test
    void reportsCancelledPaymentSessionsAsRetryableWithAStableCode() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.paymentSessionClosed(
                new PaymentSessionClosedException(SalePaymentSessionStatus.CANCELLED),
                request);

        assertEquals(409, problem.getStatus());
        assertEquals(PaymentSessionClosedException.CODE, problem.getProperties().get("code"));
        assertEquals("CANCELLED", problem.getProperties().get("paymentSessionStatus"));
        assertEquals(true, problem.getProperties().get("retryable"));
        assertEquals(
                "La sesión de cobro anterior fue cancelada. Es necesario iniciar un cobro nuevo.",
                problem.getDetail());
    }

    @Test
    void recordsStableReservationCauseAndHandlerStageOnTheRequest() {
        var request = new MockHttpServletRequest("POST", "/api/v1/pos/payment-sessions");

        var problem = handler.memberBalanceReservationConflictProblem(
                new com.tpverp.backend.party.loyalty.central.MemberBalanceReservationConflictException(
                        "reservation conflict detail", null),
                request);

        assertEquals(409, problem.getStatus());
        assertEquals("MEMBER_BALANCE_RESERVED_ELSEWHERE", problem.getProperties().get("code"));
        assertEquals(
                "MEMBER_BALANCE_RESERVED_ELSEWHERE",
                request.getAttribute(ApiExceptionContext.CAUSE_CODE_ATTRIBUTE));
        assertEquals(
                ApiExceptionContext.API_EXCEPTION_HANDLER_STAGE,
                request.getAttribute(ApiExceptionContext.STAGE_ATTRIBUTE));
        assertEquals(
                CorrelationIdFilter.getOrCreate(request),
                problem.getProperties().get("traceId"));
    }

    @Test
    void keepsOfficialSyncResponseCodeCompatibleWhileRecordingSpecificAuditCause() {
        var request = new MockHttpServletRequest("GET", "/api/v1/pos/member-wallet");

        var problem = handler.memberBalanceOfficialSyncRequired(
                new com.tpverp.backend.party.MemberBalanceOfficialSyncRequiredException(),
                request);

        assertEquals(409, problem.getStatus());
        assertEquals("STATE_CONFLICT", problem.getProperties().get("code"));
        assertEquals("El saldo de miembro necesita sincronizacion reciente", problem.getDetail());
        assertEquals(
                "MEMBER_BALANCE_OFFICIAL_SYNC_REQUIRED",
                request.getAttribute(ApiExceptionContext.CAUSE_CODE_ATTRIBUTE));
        assertEquals(
                ApiExceptionContext.API_EXCEPTION_HANDLER_STAGE,
                request.getAttribute(ApiExceptionContext.STAGE_ATTRIBUTE));
    }

    @Test
    void reportsFinalizedPaymentSessionsAsClosedButNotRetryable() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en");

        var problem = handler.paymentSessionClosed(
                new PaymentSessionClosedException(SalePaymentSessionStatus.FINALIZED),
                request);

        assertEquals(false, problem.getProperties().get("retryable"));
        assertEquals("FINALIZED", problem.getProperties().get("paymentSessionStatus"));
        assertEquals("The payment session has already been finalized.", problem.getDetail());
    }

    @Test
    void reportsFiscalQrPrintFailureAsExplicitlyRetryableWithoutLosingTheSale() {
        var documentId = UUID.randomUUID();
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");

        var problem = handler.fiscalQrUnavailable(
                new FiscalQrUnavailableException(
                        documentId,
                        FiscalQrUnavailableException.Reason.FROZEN_SNAPSHOT_MISSING),
                request);

        assertEquals(503, problem.getStatus());
        assertEquals(FiscalQrUnavailableException.CODE, problem.getProperties().get("code"));
        assertEquals(documentId.toString(), problem.getProperties().get("documentId"));
        assertEquals("FROZEN_SNAPSHOT_MISSING",
                problem.getProperties().get("fiscalQrFailure"));
        assertEquals(true, problem.getProperties().get("retryable"));
        assertEquals(
                "No se puede imprimir el documento fiscal porque su QR tributario no está disponible. La venta permanece confirmada; vuelva a intentar la impresión.",
                problem.getDetail());
    }

    @Test
    void translatesRequiredFieldValidationMessagesFromReusableParts() throws NoSuchMethodException {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
        var binding = new BeanPropertyBindingResult(new Object(), "productRequest");
        binding.addError(new FieldError(
                "productRequest", "name", "", false, new String[] {"NotBlank"}, null, "required"));
        var parameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("dummyEndpoint", Object.class), 0);

        var problem = handler.validationFailed(new MethodArgumentNotValidException(parameter, binding), request);

        assertEquals("VALIDATION_ERROR", problem.getProperties().get("code"));
        assertEquals("en", problem.getProperties().get("locale"));
        assertEquals("Product name is required", problem.getDetail());
    }

    @Test
    void reportsMethodWithoutLeakingInternalPathWhenRequestMethodIsNotSupported() {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");
        var exception = new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        var problem = handler.methodNotSupported(exception, request);

        assertEquals(405, problem.getStatus());
        assertEquals("METHOD_NOT_ALLOWED", problem.getProperties().get("code"));
        assertEquals("GET", problem.getProperties().get("method"));
        assertEquals(false, problem.getProperties().containsKey("path"));
        assertEquals("POST", problem.getProperties().get("supportedMethods"));
        assertEquals("Método GET no permitido. Usa POST.", problem.getDetail());
        assertEquals(36, String.valueOf(problem.getProperties().get("traceId")).length());
    }

    @Test
    void mapsMissingOrOutOfScopeResourcesToNotFound() {
        var problem = handler.notFound(new NoSuchElementException(), new MockHttpServletRequest());

        assertEquals(404, problem.getStatus());
        assertEquals("NOT_FOUND", problem.getProperties().get("code"));
    }

    private static UserAccount userWithLanguage(SupportedLanguage language) {
        var store = store();
        var user = new UserAccount(store, "USER", "hash", new Role(store, "VENTAS"));
        user.cambiarIdioma(language);
        return user;
    }

    private static Store store() {
        var company = new Company("B00000000", "Company", address());
        return new Store(company, "Store", address(), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @SuppressWarnings("unused")
    private static void dummyEndpoint(Object request) {
    }
}
