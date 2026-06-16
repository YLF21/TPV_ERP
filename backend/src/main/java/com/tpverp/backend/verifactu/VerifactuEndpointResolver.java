package com.tpverp.backend.verifactu;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VerifactuEndpointResolver {

    private static final Map<VerifactuEndpointMode, String> ENDPOINTS =
            new EnumMap<>(VerifactuEndpointMode.class);

    static {
        ENDPOINTS.put(VerifactuEndpointMode.PRODUCTION,
                "https://www1.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
        ENDPOINTS.put(VerifactuEndpointMode.PRODUCTION_SEAL,
                "https://www10.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
        ENDPOINTS.put(VerifactuEndpointMode.TEST,
                "https://prewww1.aeat.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
        ENDPOINTS.put(VerifactuEndpointMode.TEST_SEAL,
                "https://prewww10.aeat.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP");
    }

    public String resolve(VerifactuEndpointMode mode) {
        return ENDPOINTS.get(required(mode));
    }
    // Centraliza los endpoints publicados en el WSDL oficial de AEAT.

    private static VerifactuEndpointMode required(VerifactuEndpointMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("modo de endpoint VERI*FACTU obligatorio");
        }
        return mode;
    }
}
