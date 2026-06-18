package com.tpverp.backend.shared.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.shared.i18n.LocalizedMessages;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(new LocalizedMessages(messageSource()));

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

        assertEquals("es", problem.getProperties().get("locale"));
        assertEquals("El valor es obligatorio", problem.getDetail());
    }

    private static Usuario userWithLanguage(SupportedLanguage language) {
        var store = store();
        var user = new Usuario(store, "USER", "hash", new Rol(store, "VENTAS"));
        user.cambiarIdioma(language);
        return user;
    }

    private static Tienda store() {
        var company = new Empresa("B00000000", "Empresa", address());
        return new Tienda(company, "Tienda", address(), UUID.randomUUID().toString(),
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
}
