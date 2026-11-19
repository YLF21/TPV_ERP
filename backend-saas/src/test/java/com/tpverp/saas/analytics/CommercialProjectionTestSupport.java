package com.tpverp.saas.analytics;

import static com.tpverp.saas.SaasTestData.validCif;

import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

abstract class CommercialProjectionTestSupport {
    static final Instant BASE = Instant.parse("2025-01-01T00:00:00Z");
    static final Instant FUTURE = Instant.parse("2099-06-01T00:00:00Z");
    @Autowired EntityManager em;
    @Autowired TokenHasher tokens;
    @Autowired SyncEventService sync;

    Site site(String name, String taxSeed) {
        var company = new SaasCompany(UUID.randomUUID(), name, validCif(taxSeed), TaxpayerType.SOCIEDAD, TaxRegime.IVA, BASE);
        var store = new SaasStore(UUID.randomUUID(), company, "001", "Store " + name, "Atlantic/Canary", BASE);
        var license = new SaasLicense(UUID.randomUUID(), company, "SITE-" + name, FUTURE, 1, 0, BASE);
        String token = tokens.newToken();
        em.persist(company);
        em.persist(store);
        em.persist(license);
        em.persist(new SaasInstallation(UUID.randomUUID(), company, store, license, UUID.randomUUID(), name,
                null, tokens.hash(token), BASE));
        em.flush();
        return new Site(company, store, token);
    }

    SyncEventRequest receive(Site site, UUID id, Map<String, Object> data) {
        var operation = "ANULADO".equals(data.get("estado")) ? SyncOperation.ANULAR : SyncOperation.ACTUALIZAR;
        var request = new SyncEventRequest(UUID.randomUUID(), site.company().getId(), site.store().getId(), null,
                "DOCUMENTO", id, operation, data);
        sync.receive(request, site.token());
        return request;
    }

    static Map<String, Object> document(String type, String status, String currency, String amount, String code) {
        var data = new LinkedHashMap<String, Object>(Map.of("schemaVersion", 2, "sourceRevision", 1,
                "tipo", type, "estado", status, "numero", "DOC", "fecha", "2025-01-01",
                "subtotal", amount, "impuestos", "0", "total", amount, "moneda", currency));
        data.put("relaciones", List.of());
        data.put("lineas", List.of(Map.of("posicion", 1, "tipoLinea", "PRODUCT", "codigo", code,
                "cantidad", "1", "precioUnitario", amount, "descuento", "0", "total", amount)));
        return data;
    }

    record Site(SaasCompany company, SaasStore store, String token) {}
}
