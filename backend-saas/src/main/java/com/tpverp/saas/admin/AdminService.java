package com.tpverp.saas.admin;

import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasCompanyRepository;
import com.tpverp.saas.license.SaasLicense;
import com.tpverp.saas.license.SaasLicenseRepository;
import com.tpverp.saas.license.SaasPairingCode;
import com.tpverp.saas.license.SaasPairingCodeRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasStoreRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SaasCompanyRepository companies;
    private final SaasStoreRepository stores;
    private final SaasLicenseRepository licenses;
    private final SaasPairingCodeRepository pairingCodes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminService(
            SaasCompanyRepository companies,
            SaasStoreRepository stores,
            SaasLicenseRepository licenses,
            SaasPairingCodeRepository pairingCodes,
            Clock clock) {
        this.companies = companies;
        this.stores = stores;
        this.licenses = licenses;
        this.pairingCodes = pairingCodes;
        this.clock = clock;
    }

    @Transactional
    public CreateCompanyResponse createCompany(CreateCompanyRequest request) {
        Instant now = clock.instant();
        var company = companies.save(new SaasCompany(
                UUID.randomUUID(),
                request.name(),
                request.taxId().toUpperCase(Locale.ROOT),
                request.taxpayerType(),
                request.impuestos(),
                now));
        var store = stores.save(new SaasStore(
                UUID.randomUUID(),
                company,
                request.storeCode(),
                request.storeName() == null || request.storeName().isBlank() ? request.storeCode() : request.storeName(),
                now));
        String licenseReference = "LIC-" + company.getTaxId() + "-" + store.getCode();
        var license = licenses.save(new SaasLicense(
                UUID.randomUUID(),
                company,
                licenseReference,
                request.validUntil(),
                Math.max(1, request.maxWindows()),
                Math.max(0, request.maxPda()),
                now));
        String pairingCode = newPairingCode();
        pairingCodes.save(new SaasPairingCode(
                UUID.randomUUID(),
                company,
                store,
                license,
                pairingCode,
                now.plus(Duration.ofDays(7)),
                now));
        return new CreateCompanyResponse(company.getId(), store.getId(), licenseReference, pairingCode, license.getValidUntil());
    }

    @Transactional
    public AdminLicenseResponse block(String reference) {
        SaasLicense license = license(reference);
        license.block();
        return response(license);
    }

    @Transactional
    public AdminLicenseResponse unblock(String reference) {
        SaasLicense license = license(reference);
        license.unblock();
        return response(license);
    }

    private SaasLicense license(String reference) {
        return licenses.findByReference(reference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Licencia no existe"));
    }

    private AdminLicenseResponse response(SaasLicense license) {
        return new AdminLicenseResponse(license.getReference(), license.getStatus(), license.getValidUntil());
    }

    private String newPairingCode() {
        StringBuilder value = new StringBuilder("TPV-");
        for (int index = 0; index < 8; index++) {
            value.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return value.toString();
    }
}
