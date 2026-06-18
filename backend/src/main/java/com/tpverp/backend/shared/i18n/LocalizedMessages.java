package com.tpverp.backend.shared.i18n;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LocalizedMessages {

    private final Map<SystemErrorCode, Translation> systemErrors = new EnumMap<>(SystemErrorCode.class);
    private final Map<FieldKey, Translation> fields = new EnumMap<>(FieldKey.class);
    private final Translation required = new Translation(
            "es obligatorio",
            "is required",
            "为必填项");

    public LocalizedMessages() {
        systemErrors.put(SystemErrorCode.AUTHENTICATION_FAILED, new Translation(
                "Usuario o contraseña incorrectos",
                "Incorrect username or password",
                "用户名或密码不正确"));
        systemErrors.put(SystemErrorCode.VALIDATION_ERROR, new Translation(
                "La solicitud contiene datos no válidos",
                "The request contains invalid data",
                "请求包含无效数据"));
        systemErrors.put(SystemErrorCode.INVALID_LICENSE, new Translation(
                "La licencia no es valida",
                "The license is not valid",
                "许可证无效"));
        systemErrors.put(SystemErrorCode.STATE_CONFLICT, new Translation(
                "La operacion no es compatible con el estado actual",
                "The operation is not compatible with the current state",
                "该操作与当前状态不兼容"));
        systemErrors.put(SystemErrorCode.DATA_INTEGRITY_CONFLICT, new Translation(
                "La operacion entra en conflicto con los datos existentes",
                "The operation conflicts with existing data",
                "该操作与现有数据冲突"));
        systemErrors.put(SystemErrorCode.INTERNAL_ERROR, new Translation(
                "Error interno del sistema",
                "Internal system error",
                "系统内部错误"));

        fields.put(FieldKey.PRODUCT_CODE, new Translation("Código de producto", "Product code", "产品代码"));
        fields.put(FieldKey.PRODUCT_BARCODE, new Translation("Código de barras", "Barcode", "条形码"));
        fields.put(FieldKey.PRODUCT_NAME, new Translation("Nombre de producto", "Product name", "产品名称"));
    }

    public String system(SystemErrorCode code, SupportedLanguage language) {
        return systemErrors.getOrDefault(code, systemErrors.get(SystemErrorCode.INTERNAL_ERROR))
                .text(language);
    }

    public String required(FieldKey field, SupportedLanguage language) {
        var selected = SupportedLanguageFallback.value(language);
        var separator = selected == SupportedLanguage.ZH ? "" : " ";
        return fields.get(field).text(selected) + separator + required.text(selected);
    }

    private record Translation(String es, String en, String zh) {

        String text(SupportedLanguage language) {
            return switch (SupportedLanguageFallback.value(language)) {
                case EN -> en;
                case ZH -> zh;
                case ES -> es;
            };
        }
    }

    private static final class SupportedLanguageFallback {

        static SupportedLanguage value(SupportedLanguage language) {
            return language == null ? SupportedLanguage.ES : language;
        }
    }
}
