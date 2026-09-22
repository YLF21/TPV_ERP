package com.tpverp.saas;

import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.CreateCompanyRequest;
import com.tpverp.saas.admin.CreateTenantUserRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class SaasTestData {

    private static final String CIF_CONTROL = "JABCDEFGHI";

    private SaasTestData() {
    }

    public static Map<String, String> fiscalAddress() {
        return Map.of(
                "linea1", "Calle Pruebas 1",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }

    @FunctionalInterface
    public interface JsonPost { JsonNode apply(String path, Object payload) throws Exception; }

    public static java.util.List<com.tpverp.saas.admin.CompanyOwner> companyOwners() {
        return java.util.List.of(new com.tpverp.saas.admin.CompanyOwner(
                "Propietario de prueba", "12345678Z", null, null));
    }

    /** Test fixture using the same three independent HTTP stages as the application. */
    public static ProvisionedCompany provisionCompany(MockMvc mvc, ObjectMapper mapper,
            ProvisioningRequest request) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        return provisionCompany((path, payload) -> mapper.readTree(mvc.perform(post(path)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(payload))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()), request);
    }

    public static ProvisionedCompany provisionCompany(JsonPost post, ProvisioningRequest request) throws Exception {
        JsonNode company = post.apply("/api/v1/admin/companies", new CreateCompanyRequest(request.name(),
                request.taxId(), request.taxpayerType(), request.commercialProfile(), request.companyAddress(), request.owners()));
        String companyId = company.get("companyId").asText();
        var storeRequest = new java.util.LinkedHashMap<String, Object>(Map.of(
                "code", request.storeCode(), "name", request.storeName(), "storeAddress", request.storeAddress(),
                "timeZoneId", request.timeZoneId(), "taxRegime", request.impuestos(),
                "servicePrice", "25.00", "billingPeriod", "MONTHLY", "validUntil", request.validUntil(),
                "maxWindows", request.maxWindows(), "maxPda", request.maxPda()));
        storeRequest.put("commercialProfile", request.commercialProfile());
        JsonNode store = post.apply("/api/v1/admin/companies/" + companyId + "/stores", storeRequest);
        UUID storeId = UUID.fromString(store.get("id").asText());
        JsonNode license = post.apply("/api/v1/admin/license-workspace", Map.of("storeId", storeId));
        String username = "fixture-" + companyId;
        String password = "fixture-tenant-pass";
        post.apply("/api/v1/admin/companies/" + companyId + "/tenant-users", new CreateTenantUserRequest(
                username, password, "MANAGER", Set.of(storeId),
                java.util.EnumSet.allOf(com.tpverp.saas.access.TenantCompanyPrivilege.class)));
        return new ProvisionedCompany(UUID.fromString(companyId), storeId, license.get("reference").asText(),
                license.get("pairingCode").asText(), request.validUntil(), username, password);
    }

    /** Conserva el prefijo y los siete digitos de un CIF semilla y calcula su control. */
    public static String validCif(String seed) {
        String normalized = seed.replace("-", "").replace(" ", "").toUpperCase();
        if (normalized.length() != 9 || !normalized.substring(1, 8).matches("\\d{7}")) {
            throw new IllegalArgumentException("Semilla CIF no valida: " + seed);
        }
        int sum = 0;
        String digits = normalized.substring(1, 8);
        for (int index = 0; index < digits.length(); index++) {
            int digit = digits.charAt(index) - '0';
            if (index % 2 == 0) {
                int doubled = digit * 2;
                sum += doubled / 10 + doubled % 10;
            } else {
                sum += digit;
            }
        }
        int control = (10 - sum % 10) % 10;
        char prefix = normalized.charAt(0);
        char suffix = switch (prefix) {
            case 'P', 'Q', 'S' -> CIF_CONTROL.charAt(control);
            default -> Character.forDigit(control, 10);
        };
        return prefix + digits + suffix;
    }
}
