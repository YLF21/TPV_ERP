package com.tpverp.backend.document;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class SaleDocumentAuthorizationFingerprint {

    public String fingerprint(CommercialDocument document) {
        var digest = sha256();
        add(digest, "sale-document-authorization-v1");
        add(digest, document.getId());
        add(digest, document.getTiendaId());
        add(digest, document.getAlmacenId());
        add(digest, document.getTipo());
        add(digest, document.getFecha());
        add(digest, document.getClienteId());
        add(digest, document.getProveedorId());
        add(digest, document.getNumeroExterno());
        add(digest, document.getComentarioInterno());
        add(digest, document.getDescuentoGlobal());
        add(digest, document.getMoneda());
        add(digest, document.getFechaVencimiento());
        add(digest, document.isOrigenStock());
        add(digest, document.isCuentaCobrar());
        add(digest, document.getBaseTotal());
        add(digest, document.getImpuestoTotal());
        add(digest, document.getTotal());
        for (var line : document.getLineas()) {
            add(digest, line.getPosicion());
            add(digest, line.getProductoId());
            add(digest, line.getLineType());
            add(digest, line.getPromotionId());
            add(digest, line.getPromotionVersionId());
            add(digest, line.getPromotionalCouponId());
            add(digest, line.getOriginalDocumentLineId());
            add(digest, line.getCantidad());
            add(digest, line.getCodigo());
            add(digest, line.getNombre());
            add(digest, line.getTarifa());
            add(digest, line.getPrecioUnitario());
            add(digest, line.getDescuento());
            add(digest, line.isImpuestosIncluidos());
            add(digest, line.getRegimenImpuesto());
            add(digest, line.getPorcentajeImpuesto());
            add(digest, line.getBase());
            add(digest, line.getImpuesto());
            add(digest, line.getTotal());
            add(digest, line.getSerialNumbers().size());
            line.getSerialNumbers().forEach(value -> add(digest, value));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static void add(MessageDigest digest, Object value) {
        var normalized = value == null
                ? "<null>"
                : value instanceof BigDecimal decimal
                        ? decimal.stripTrailingZeros().toPlainString()
                        : value.toString();
        var bytes = normalized.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
