package com.tpverp.backend.shared.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.application.RoleInUseException;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.shared.i18n.LocalizedMessages;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
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
    void translatesLegacyBusinessMessagesWhenKnown() {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");

        var problem = handler.stateConflict(
                new IllegalStateException("No se puede eliminar un producto con historial"), request);

        assertEquals("en", problem.getProperties().get("locale"));
        assertEquals("A product with history cannot be deleted", problem.getDetail());
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
    void reportsMethodAndPathWhenRequestMethodIsNotSupported() {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "es-ES");
        var exception = new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        var problem = handler.methodNotSupported(exception, request);

        assertEquals(405, problem.getStatus());
        assertEquals("METHOD_NOT_ALLOWED", problem.getProperties().get("code"));
        assertEquals("GET", problem.getProperties().get("method"));
        assertEquals("/api/v1/auth/login", problem.getProperties().get("path"));
        assertEquals("POST", problem.getProperties().get("supportedMethods"));
        assertEquals("Metodo GET no permitido para /api/v1/auth/login. Usa POST.", problem.getDetail());
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
