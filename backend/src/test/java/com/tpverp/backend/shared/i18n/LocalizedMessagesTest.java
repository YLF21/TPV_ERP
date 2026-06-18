package com.tpverp.backend.shared.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LocalizedMessagesTest {

    private final LocalizedMessages messages = new LocalizedMessages();

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
    void resolvesSupportedLanguageFromHttpHeader() {
        assertEquals(SupportedLanguage.EN, SupportedLanguage.fromHeader("en-US,en;q=0.9"));
        assertEquals(SupportedLanguage.ZH, SupportedLanguage.fromHeader("zh-CN,zh;q=0.9"));
        assertEquals(SupportedLanguage.ES, SupportedLanguage.fromHeader("fr-FR,fr;q=0.9"));
        assertEquals(SupportedLanguage.ES, SupportedLanguage.fromHeader(null));
    }
}
