package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The issuer rows used by the normal ticket header, read for a gift receipt. */
@Repository
public class GiftReceiptPrintIdentityRepository {

    private final JdbcTemplate jdbc;

    public GiftReceiptPrintIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Identity forSourceTicket(UUID documentId, UUID storeId) {
        return jdbc.queryForObject("""
                SELECT
                    CASE WHEN COALESCE(cdi.mostrar_nombre_tienda, TRUE)
                        THEN COALESCE(NULLIF(TRIM(t.nombre), ''),
                            CASE WHEN fiscal.registro_id IS NULL
                                THEN NULLIF(TRIM(e.razon_social), '')
                                ELSE fiscal.obligado_nombre END)
                        ELSE CASE WHEN fiscal.registro_id IS NULL
                            THEN COALESCE(NULLIF(TRIM(e.razon_social), ''), NULLIF(TRIM(t.nombre), ''))
                            ELSE fiscal.obligado_nombre END
                    END AS primary_name,
                    CASE WHEN fiscal.registro_id IS NULL THEN e.razon_social
                        ELSE fiscal.obligado_nombre END AS legal_name,
                    CASE WHEN COALESCE(cdi.mostrar_nombre_tienda, TRUE)
                        THEN CASE WHEN fiscal.registro_id IS NULL
                            THEN NULLIF(TRIM(e.razon_social), '')
                            ELSE fiscal.obligado_nombre END
                        ELSE NULL END AS secondary_name,
                    CASE WHEN fiscal.registro_id IS NULL THEN e.tax_id
                        ELSE fiscal.obligado_nif END AS tax_id,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.telefono
                        ELSE fiscal.obligado_direccion ->> 'telefono' END AS phone,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.email
                        ELSE fiscal.obligado_direccion ->> 'email' END AS email,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.direccion ->> 'linea1'
                        ELSE fiscal.obligado_direccion ->> 'linea1' END AS address_line1,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.direccion ->> 'codigoPostal'
                        ELSE fiscal.obligado_direccion ->> 'codigoPostal' END AS postal_code,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.direccion ->> 'ciudad'
                        ELSE fiscal.obligado_direccion ->> 'ciudad' END AS city,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.direccion ->> 'provincia'
                        ELSE fiscal.obligado_direccion ->> 'provincia' END AS province,
                    CASE WHEN fiscal.registro_id IS NULL THEN t.direccion ->> 'pais'
                        ELSE fiscal.obligado_direccion ->> 'pais' END AS country
                FROM documento d
                JOIN tienda t ON t.id = d.tienda_id
                JOIN empresa e ON e.id = t.empresa_id
                LEFT JOIN configuracion_documento_impreso_tienda cdi ON cdi.tienda_id = t.id
                LEFT JOIN LATERAL (
                    SELECT rf.id AS registro_id, arf.obligado_nombre,
                        arf.obligado_nif, arf.obligado_direccion
                    FROM registro_fiscal rf
                    JOIN artefacto_registro_fiscal arf ON arf.registro_id = rf.id
                    WHERE rf.documento_id = d.id AND rf.operacion = 'ALTA'
                    ORDER BY rf.secuencia DESC LIMIT 1
                ) fiscal ON TRUE
                WHERE d.id = ? AND d.tienda_id = ?
                """, (rs, rowNum) -> new Identity(
                        rs.getString("primary_name"), rs.getString("legal_name"),
                        rs.getString("secondary_name"),
                        rs.getString("tax_id"), rs.getString("phone"), rs.getString("email"),
                        rs.getString("address_line1"), rs.getString("postal_code"),
                        rs.getString("city"), rs.getString("province"), rs.getString("country")),
                documentId, storeId);
    }

    public record Identity(
            String primaryName,
            String legalName,
            String secondaryName,
            String taxId,
            String phone,
            String email,
            String addressLine1,
            String postalCode,
            String city,
            String province,
            String country) {
    }
}
