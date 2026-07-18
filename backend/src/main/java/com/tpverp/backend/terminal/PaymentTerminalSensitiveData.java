package com.tpverp.backend.terminal;

import java.util.Locale;

final class PaymentTerminalSensitiveData {
    private PaymentTerminalSensitiveData() {}
    static String mask(String value){
        if(value==null)return null;
        return java.util.regex.Pattern.compile("(?<!\\d)(?:\\d[ -]?){8,18}\\d(?!\\d)").matcher(value).replaceAll(match -> {
            var digits=match.group().replaceAll("\\D","");
            return "****"+digits.substring(Math.max(0,digits.length()-4));
        });
    }
    static String storageIdentifier(String value,int maximum){
        if(value==null||value.isBlank())return null;
        var safe=mask(value.trim());
        safe=java.util.regex.Pattern.compile("(?i)\\b(secret|token|password|cvv|cvc|pin)\\b\\s*[:=]?\\s*[^\\s,;]*")
                .matcher(safe).replaceAll(match->match.group(1)+"=[REDACTED]");
        if(safe.length()>maximum)throw new IllegalArgumentException("Identificador externo demasiado largo");
        return safe;
    }
    static boolean sensitiveKey(String key){var lower=key.toLowerCase(Locale.ROOT);
        return lower.contains("secret")||lower.contains("token")||lower.contains("password")
                ||lower.equals("apikey")||lower.equals("api_key")||lower.contains("credential")||lower.contains("privatekey")
                ||lower.contains("pan")||lower.contains("cvv")||lower.contains("cvc")
                ||lower.contains("pin")||lower.contains("authorization");}
    static PaymentTerminalResult safe(PaymentTerminalResult result){return new PaymentTerminalResult(result.status(),result.code(),
            mask(result.reference()),mask(result.authorization()),mask(result.message()),result.finalOutcome());}
}
