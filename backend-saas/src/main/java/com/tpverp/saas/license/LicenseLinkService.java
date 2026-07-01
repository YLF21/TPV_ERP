package com.tpverp.saas.license;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class LicenseLinkService {

    private final SaasPairingCodeRepository pairingCodes;
    private final SaasInstallationRepository installations;
    private final TokenHasher tokens;
    private final Clock clock;

    public LicenseLinkService(
            SaasPairingCodeRepository pairingCodes,
            SaasInstallationRepository installations,
            TokenHasher tokens,
            Clock clock) {
        this.pairingCodes = pairingCodes;
        this.installations = installations;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public LicenseSaasLinkResponse link(LicenseSaasLinkRequest request) {
        Instant now = clock.instant();
        SaasPairingCode pairing = pairingCodes.findByCode(request.pairingCode())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Codigo de enlace no existe"));
        if (!pairing.usableAt(now)) {
            throw new ResponseStatusException(CONFLICT, "Codigo de enlace caducado o usado");
        }
        String token = tokens.newToken();
        installations.save(new SaasInstallation(
                UUID.randomUUID(),
                pairing.getCompany(),
                pairing.getStore(),
                pairing.getLicense(),
                request.installationId(),
                request.installationReference(),
                request.installationPublicKey(),
                tokens.hash(token),
                now));
        pairing.consume(now);
        return response(pairing, token);
    }

    private LicenseSaasLinkResponse response(SaasPairingCode pairing, String token) {
        SaasCompany company = pairing.getCompany();
        SaasStore store = pairing.getStore();
        SaasLicense license = pairing.getLicense();
        return new LicenseSaasLinkResponse(
                license.getReference(),
                company.getId(),
                store.getId(),
                license.getValidUntil(),
                license.getStatus(),
                license.getMaxWindows(),
                license.getMaxPda(),
                company.getTaxId(),
                company.getTaxpayerType(),
                company.getTaxRegime(),
                token);
    }
}
