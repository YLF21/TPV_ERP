package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.SpanishTaxId;
import java.util.Locale;
import javax.naming.ldap.LdapName;
import javax.security.auth.x500.X500Principal;
import org.springframework.stereotype.Component;

@Component
public class CertificateTaxIdExtractor {

    // Extrae el identificador fiscal de los atributos normalizados usados en certificados espanoles.
    public String extract(X500Principal principal) {
        try {
            for (var rdn : new LdapName(principal.getName(X500Principal.RFC2253)).getRdns()) {
                var type = rdn.getType().toUpperCase(Locale.ROOT);
                if (type.equals("SERIALNUMBER")
                        || type.equals("2.5.4.5")
                        || type.equals("2.5.4.97")) {
                    var candidate = normalizeAttribute(attributeValue(rdn.getValue()));
                    try {
                        return SpanishTaxId.validate(candidate);
                    } catch (IllegalArgumentException ignored) {
                        // Continua por si el sujeto contiene otro atributo fiscal valido.
                    }
                }
            }
        } catch (javax.naming.InvalidNameException exception) {
            throw new IllegalArgumentException("El sujeto del certificado no es valido", exception);
        }
        throw new IllegalArgumentException("No se pudo extraer el NIF del certificado");
    }

    private static String normalizeAttribute(String value) {
        return value.trim().toUpperCase(Locale.ROOT)
                .replaceFirst("^(IDCES-|VATES-|NIF[:=]?)", "");
    }

    private static String attributeValue(Object value) {
        if (!(value instanceof byte[] encoded)) {
            return String.valueOf(value);
        }
        if (encoded.length < 2) {
            return "";
        }
        var lengthByte = encoded[1] & 0xFF;
        var offset = 2;
        var length = lengthByte;
        if ((lengthByte & 0x80) != 0) {
            var lengthBytes = lengthByte & 0x7F;
            if (lengthBytes == 0 || lengthBytes > 4 || encoded.length < 2 + lengthBytes) {
                return "";
            }
            length = 0;
            for (var index = 0; index < lengthBytes; index++) {
                length = (length << 8) | (encoded[offset++] & 0xFF);
            }
        }
        if (length < 0 || offset + length > encoded.length) {
            return "";
        }
        var charset = switch (encoded[0] & 0xFF) {
            case 0x0C -> java.nio.charset.StandardCharsets.UTF_8;
            case 0x13, 0x16 -> java.nio.charset.StandardCharsets.US_ASCII;
            case 0x1E -> java.nio.charset.StandardCharsets.UTF_16BE;
            default -> null;
        };
        return charset == null ? "" : new String(encoded, offset, length, charset);
    }
}
