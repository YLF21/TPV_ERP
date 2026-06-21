package com.tpverp.backend.shared.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class LocalizedMessagesTest {

    private final LocalizedMessages messages = new LocalizedMessages(messageSource());

    @Test
    void translatesSystemErrorsUsingSpanishAsFallback() {
        assertEquals("Usuario o contraseña incorrectos",
                messages.system(SystemErrorCode.AUTHENTICATION_FAILED, SupportedLanguage.ES));
        assertEquals("Incorrect username or password",
                messages.system(SystemErrorCode.AUTHENTICATION_FAILED, SupportedLanguage.EN));
        assertEquals("用户名或密码不正确",
                messages.system(SystemErrorCode.AUTHENTICATION_FAILED, SupportedLanguage.ZH));
        assertEquals("Usuario o contraseña incorrectos",
                messages.system(SystemErrorCode.AUTHENTICATION_FAILED, null));
    }

    @Test
    void buildsRequiredFieldMessagesFromReusableParts() {
        assertEquals("Nombre de producto es obligatorio",
                messages.required(FieldKey.PRODUCT_NAME, SupportedLanguage.ES));
        assertEquals("Product name is required",
                messages.required(FieldKey.PRODUCT_NAME, SupportedLanguage.EN));
        assertEquals("产品名称为必填项",
                messages.required(FieldKey.PRODUCT_NAME, SupportedLanguage.ZH));
    }

    @Test
    void translatesLegacyRequiredFieldsWithoutEsVariant() {
        assertEquals("Certificate path is required",
                messages.legacy("ruta de certificado obligatorio", SupportedLanguage.EN).orElseThrow());
    }

    @Test
    void translatesRemainingLiteralBackendErrors() {
        assertEquals("Certificate file not found",
                messages.legacy("archivo de certificado PKCS#12 no encontrado", SupportedLanguage.EN).orElseThrow());
        assertEquals("X509 certificate not found",
                messages.legacy("certificado X509 no encontrado", SupportedLanguage.EN).orElseThrow());
        assertEquals("Export format is required",
                messages.legacy("El formato de exportacion es obligatorio", SupportedLanguage.EN).orElseThrow());
        assertEquals("VERI*FACTU XML could not be generated",
                messages.legacy("No se pudo generar el XML VERI*FACTU", SupportedLanguage.EN).orElseThrow());
    }

    @Test
    void translatesVerifactuDomainErrors() {
        assertEquals("The sales invoice must be F1 or F3",
                messages.legacy("La factura de venta debe ser F1 o F3", SupportedLanguage.EN).orElseThrow());
        assertEquals("The ticket must be in CONFIRMED state",
                messages.legacy("El ticket debe estar en estado CONFIRMADO", SupportedLanguage.EN).orElseThrow());
        assertEquals("The ticket must be registered as R5",
                messages.legacy("El ticket debe registrarse como R5", SupportedLanguage.EN).orElseThrow());
        assertEquals("The fiscal operation is already registered for the document",
                messages.legacy("La operacion fiscal ya esta registrada para el documento",
                        SupportedLanguage.EN).orElseThrow());
        assertEquals("The license tax ID does not match the company",
                messages.legacy("El NIF de la licencia no coincide con la empresa",
                        SupportedLanguage.EN).orElseThrow());
    }

    @Test
    void resolvesSupportedLanguageFromHttpHeader() {
        assertEquals(SupportedLanguage.EN, SupportedLanguage.fromHeader("en-US,en;q=0.9"));
        assertEquals(SupportedLanguage.ZH, SupportedLanguage.fromHeader("zh-CN,zh;q=0.9"));
        assertEquals(SupportedLanguage.ES, SupportedLanguage.fromHeader("fr-FR,fr;q=0.9"));
        assertEquals(SupportedLanguage.ES, SupportedLanguage.fromHeader(null));
    }

    @Test
    void everySpanishMessageKeyExistsInEnglishAndChinese() {
        var spanish = ResourceBundle.getBundle("i18n/messages", java.util.Locale.forLanguageTag("es"));
        var english = ResourceBundle.getBundle("i18n/messages", java.util.Locale.forLanguageTag("en"));
        var chinese = ResourceBundle.getBundle("i18n/messages", java.util.Locale.forLanguageTag("zh"));

        spanish.keySet().forEach(key -> {
            assertTrue(english.containsKey(key), "Missing English translation for " + key);
            assertTrue(chinese.containsKey(key), "Missing Chinese translation for " + key);
        });
    }

    @Test
    void everyFieldKeyHasTranslationsInAllLanguages() {
        var spanish = ResourceBundle.getBundle("i18n/messages", java.util.Locale.forLanguageTag("es"));
        var english = ResourceBundle.getBundle("i18n/messages", java.util.Locale.forLanguageTag("en"));
        var chinese = ResourceBundle.getBundle("i18n/messages", java.util.Locale.forLanguageTag("zh"));

        for (var field : FieldKey.values()) {
            var key = "field." + field.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(spanish.containsKey(key), "Missing Spanish field translation for " + key);
            assertTrue(english.containsKey(key), "Missing English field translation for " + key);
            assertTrue(chinese.containsKey(key), "Missing Chinese field translation for " + key);
        }
    }

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }
}
