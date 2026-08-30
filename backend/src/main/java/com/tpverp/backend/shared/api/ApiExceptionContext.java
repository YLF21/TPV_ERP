package com.tpverp.backend.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/** Request-scoped stable failure context shared by the API handler and auditing. */
public final class ApiExceptionContext {

    public static final String CAUSE_CODE_ATTRIBUTE = ApiExceptionContext.class.getName() + ".causeCode";
    public static final String STAGE_ATTRIBUTE = ApiExceptionContext.class.getName() + ".stage";
    public static final String API_EXCEPTION_HANDLER_STAGE = "api.exception_handler";
    public static final String UNKNOWN_ERROR_CODE = "UNKNOWN_ERROR";

    private static final int MAX_CODE_LENGTH = 128;
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private ApiExceptionContext() {
    }

    public static void record(HttpServletRequest request, String code, String stage) {
        if (request == null) {
            return;
        }
        request.setAttribute(CAUSE_CODE_ATTRIBUTE, normalizeCode(code));
        request.setAttribute(STAGE_ATTRIBUTE, stage == null || stage.isBlank() ? "unknown" : stage);
    }

    public static String causeCode(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        var value = request.getAttribute(CAUSE_CODE_ATTRIBUTE);
        return value instanceof String code ? normalizeCodeOrNull(code) : null;
    }

    public static String stage(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        var value = request.getAttribute(STAGE_ATTRIBUTE);
        if (!(value instanceof String stage) || stage.isBlank()) {
            return null;
        }
        var normalized = stage.trim();
        return normalized.length() <= MAX_CODE_LENGTH && normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
                ? normalized
                : null;
    }

    public static String normalizeCode(String code) {
        var normalized = normalizeCodeOrNull(code);
        return normalized == null ? UNKNOWN_ERROR_CODE : normalized;
    }

    private static String normalizeCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        var normalized = code.trim();
        return SAFE_CODE.matcher(normalized).matches() ? normalized : null;
    }
}
