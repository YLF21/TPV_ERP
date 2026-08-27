package com.tpverp.saas.license;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LicenseLinkService {

    private static final String LOCAL_DEMO_TAX_ID = "DEMO-00000000";

    private final SaasPairingCodeRepository pairingCodes;
    private final SaasLicenseRepository licenses;
    private final SaasInstallationRepository installations;
    private final TokenHasher tokens;
    private final InstallationAuthenticator authenticator;
    private final Clock clock;
    private final VerifactuActivationPolicyResolver verifactuPolicies;

    public LicenseLinkService(
            SaasPairingCodeRepository pairingCodes,
            SaasLicenseRepository licenses,
            SaasInstallationRepository installations,
            TokenHasher tokens,
            InstallationAuthenticator authenticator,
            Clock clock,
            VerifactuActivationPolicyResolver verifactuPolicies) {
        this.pairingCodes = pairingCodes;
        this.licenses = licenses;
        this.installations = installations;
        this.tokens = tokens;
        this.authenticator = authenticator;
        this.clock = clock;
        this.verifactuPolicies = verifactuPolicies;
    }

    @Transactional
    public LicenseSaasLinkResponse link(
            LicenseSaasLinkRequest request,
            String previousToken,
            String recoveryToken) {
        Instant now = clock.instant();
        ValidatedRequest validatedRequest = validateRequest(request);
        // Lock order is global: license first, pairing second. The unlocked read
        // only discovers the owning license; all decisions use the reloaded,
        // pessimistically locked pairing after acquiring the license row.
        SaasPairingCode pairingSnapshot = pairingCodes.findFirstByCode(
                        validatedRequest.pairingCode())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Codigo de enlace no existe"));
        SaasLicense lockedLicense = licenses.findByReferenceForUpdate(
                        pairingSnapshot.getLicense().getReference())
                .orElseThrow(() -> conflict("La licencia del codigo de enlace no existe"));
        SaasPairingCode pairing = pairingCodes.findByCodeForUpdate(
                        validatedRequest.pairingCode())
                .orElseThrow(() -> conflict("El codigo de enlace cambio durante la vinculacion"));
        if (!pairing.getId().equals(pairingSnapshot.getId())
                || !pairing.getLicense().getReference().equals(lockedLicense.getReference())) {
            throw conflict("El codigo de enlace cambio durante la vinculacion");
        }

        var existingInstallation = installations.findByInstallationIdForUpdate(
                validatedRequest.installationId());
        if (existingInstallation.isPresent()
                && !existingInstallation.get().getLicense().getReference()
                        .equals(lockedLicense.getReference())) {
            throw conflict("La instalacion ya esta vinculada a otra licencia");
        }
        if (existingInstallation.isPresent()
                && !existingInstallation.get().getStore().getId().equals(pairing.getStore().getId())) {
            throw conflict("La instalacion ya esta vinculada a otra tienda");
        }
        if (!pairing.usableAt(now)) {
            if (pairing.getConsumedAt() == null
                    || pairing.getConsumedInstallation() == null
                    || !pairing.getConsumedInstallation().getInstallationId()
                            .equals(validatedRequest.installationId())
                    || existingInstallation.isEmpty()) {
                throw conflict("Codigo de enlace caducado o usado");
            }
            if (!existingInstallation.get().isCurrentPairing(pairing.getId())) {
                throw conflict("El codigo de enlace ya no es el vigente para esta instalacion");
            }
            // El UUID y el codigo ya consumido no son secretos. La recuperacion
            // autentica con el contexto congelado al consumirlo. La credencial
            // derivada es estable: todos los reintentos del mismo intento reciben
            // el mismo token, incluso si una segunda respuesta tambien se pierde.
            String retryCredential = requireRetryCredential(
                    pairing, existingInstallation.get(), previousToken, recoveryToken);
            String token = tokens.deriveRecoveredToken(
                    retryCredential, pairing.getId(), validatedRequest.installationId());
            String recoveredTokenHash = tokens.hash(token);
            if (!existingInstallation.get().isActive()) {
                if (!existingInstallation.get().hasTokenHash(recoveredTokenHash)) {
                    throw conflict("La instalacion revocada no conserva la credencial del intento");
                }
            } else {
                existingInstallation.get().usePairing(pairing.getId());
            }
            if (existingInstallation.get().isActive()
                    && !existingInstallation.get().hasTokenHash(recoveredTokenHash)) {
                existingInstallation.get().rotateTokenHash(tokens.hash(token));
            }
            installations.save(existingInstallation.get());
            LicenseSaasStatus recoveryStatus = !existingInstallation.get().isActive()
                    ? LicenseSaasStatus.BLOQUEADA_MANUAL
                    : lockedLicense.getStatus() == LicenseSaasStatus.VALIDA
                            && (lockedLicense.getValidUntil() == null
                                || !now.isBefore(lockedLicense.getValidUntil()))
                            ? LicenseSaasStatus.CADUCADA
                            : lockedLicense.getStatus();
            return response(resolvePairingSnapshot(
                    pairing, lockedLicense, request, now, false), token, recoveryStatus);
        }

        if (existingInstallation.isPresent() && !existingInstallation.get().isActive()) {
            throw conflict("La instalacion esta revocada y no puede volver a activarse");
        }

        // El preflight completo precede a cualquier rotacion, alta o consumo. De este
        // modo un dato central/fiscal incompleto no invalida un codigo nuevo.
        ValidatedPairing validatedPairing = resolvePairingSnapshot(
                pairing, lockedLicense, request, now, true);

        if (existingInstallation.isEmpty()
                && installations.existsByStore_IdAndActiveTrue(pairing.getStore().getId())) {
            throw conflict(
                    "La tienda ya tiene una instalacion activa; revoquela antes de vincular otra");
        }

        SaasInstallation linkedInstallation;
        String attemptRecoveryToken = optionalRecoveryToken(recoveryToken);
        String previousTokenHash = null;
        String retryCredential;
        String token;
        if (existingInstallation.isPresent()) {
            linkedInstallation = existingInstallation.get();
            // A fresh pairing code is not proof of ownership of an already
            // linked installation. Recovery without the current installation
            // token is intentionally limited to the consumed-pairing retry above.
            authenticator.requireToken(linkedInstallation, previousToken);
            previousTokenHash = linkedInstallation.tokenHashSnapshot();
            retryCredential = attemptRecoveryToken == null
                    ? previousToken.trim() : attemptRecoveryToken;
            token = tokens.deriveRecoveredToken(
                    retryCredential, pairing.getId(), validatedRequest.installationId());
            linkedInstallation.usePairing(pairing.getId());
            linkedInstallation.rotateTokenHash(tokens.hash(token));
            installations.save(linkedInstallation);
        } else {
            String requiredRecoveryToken = requiredRecoveryToken(attemptRecoveryToken);
            retryCredential = requiredRecoveryToken;
            token = tokens.deriveRecoveredToken(
                    retryCredential, pairing.getId(), validatedRequest.installationId());
            linkedInstallation = installations.save(new SaasInstallation(
                    UUID.randomUUID(),
                    pairing.getCompany(),
                    pairing.getStore(),
                    lockedLicense,
                    validatedRequest.installationId(),
                    validatedRequest.installationReference(),
                    validatedRequest.installationPublicKey(),
                    tokens.hash(token),
                    tokens.hash(requiredRecoveryToken),
                    now));
            linkedInstallation.usePairing(pairing.getId());
        }
        pairing.consume(
                now,
                linkedInstallation,
                attemptRecoveryToken == null ? null : tokens.hash(attemptRecoveryToken),
                previousTokenHash);
        return response(validatedPairing, token, lockedLicense.getStatus());
    }

    private String requireRetryCredential(
            SaasPairingCode pairing,
            SaasInstallation installation,
            String previousToken,
            String recoveryToken) {
        if (recoveryToken != null && !recoveryToken.isBlank()) {
            String normalizedRecovery = recoveryToken.trim();
            String recoveryHash = tokens.hash(normalizedRecovery);
            if (pairing.hasLinkRecoveryTokenHash(recoveryHash)
                    || (!pairing.hasRetryCredentialContext()
                    && installation.hasLinkRecoveryTokenHash(recoveryHash))) {
                return normalizedRecovery;
            }
        }
        if (pairing.hasLinkRecoveryCredentialContext()) {
            authenticator.requireToken(installation, previousToken);
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "El intento consumido exige su credencial de recuperacion");
        }
        if (previousToken != null && !previousToken.isBlank()) {
            String previousHash = tokens.hash(previousToken);
            if (pairing.hasPreviousInstallationTokenHash(previousHash)) {
                return previousToken;
            }
            // Compatibility for pairings consumed before V33 and for callers
            // that know the first token but no longer retain the recovery secret.
            if (!pairing.hasRetryCredentialContext()
                    && installation.hasTokenHash(previousHash)) {
                pairing.rememberPreviousInstallationTokenHash(previousHash);
                return previousToken;
            }
        }
        authenticator.requireToken(installation, previousToken);
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "La credencial no pertenece al intento de enlace consumido");
    }

    private String requiredRecoveryToken(String recoveryToken) {
        if (recoveryToken == null) {
            throw badRequest("X-TPV-Link-Recovery-Token es obligatorio para el primer enlace");
        }
        return recoveryToken;
    }

    private String optionalRecoveryToken(String recoveryToken) {
        if (recoveryToken == null || recoveryToken.isBlank()) {
            return null;
        }
        String normalized = recoveryToken.trim();
        if (normalized.length() < 43 || normalized.length() > 512) {
            throw badRequest("X-TPV-Link-Recovery-Token no tiene una longitud valida");
        }
        return normalized;
    }

    private ValidatedRequest validateRequest(LicenseSaasLinkRequest request) {
        if (request == null) {
            throw badRequest("La solicitud de enlace es obligatoria");
        }
        if (request.installationId() == null) {
            throw badRequest("installationId es obligatorio");
        }
        return new ValidatedRequest(
                requestValue(() -> LicenseProvisioningData.requiredCode(
                        request.pairingCode(), "pairingCode")),
                request.installationId(),
                requestValue(() -> LicenseProvisioningData.requiredName(
                        request.installationReference(), "installationReference", 120)),
                requestValue(() -> LicenseProvisioningData.optionalText(
                        request.installationPublicKey(), 16_384, "installationPublicKey")));
    }

    private ValidatedPairing resolvePairingSnapshot(
            SaasPairingCode pairing,
            SaasLicense license,
            LicenseSaasLinkRequest request,
            Instant now,
            boolean validateNewLink) {
        SaasCompany company = pairing.getCompany();
        SaasStore store = pairing.getStore();

        String companyTaxId = configurationValue(() -> SpanishTaxId.validate(company.getTaxId()));
        String companyName = configurationValue(() -> LicenseProvisioningData.requiredName(
                company.getName(), "nombre de empresa SaaS", 200));
        String storeCode = configurationValue(() -> LicenseProvisioningData.storeCode(
                store.getCode()));
        String storeName = configurationValue(() -> LicenseProvisioningData.requiredName(
                store.getName(), "nombre de tienda SaaS", 200));
        String timeZoneId = configurationValue(() -> LicenseProvisioningData.timeZoneId(
                store.getTimeZoneId()));
        String licenseReference = configurationValue(() -> LicenseProvisioningData.requiredName(
                license.getReference(), "referencia de licencia", 80));

        if (validateNewLink) {
            validateRequestedIdentity(request, companyTaxId, storeCode, timeZoneId);
        }

        Map<String, String> companyAddress = resolveAddress(
                company.getCompanyAddress(), request.companyAddress(),
                "domicilio fiscal de la empresa SaaS", "companyAddress");
        Map<String, String> storeAddress = resolveAddress(
                store.getStoreAddress(), request.storeAddress(),
                "domicilio de la tienda SaaS", "storeAddress");

        if (validateNewLink) {
            if (license.getValidUntil() == null || !license.getValidUntil().isAfter(now)) {
                throw conflict("La licencia esta caducada y no puede vincularse");
            }
            if (license.getStatus() != LicenseSaasStatus.VALIDA) {
                throw conflict("La licencia no esta activa y no puede vincularse");
            }
            if (license.getMaxWindows() < 1 || license.getMaxPda() < 0) {
                throw conflict("Los cupos de la licencia SaaS no son validos");
            }
        }

        VerifactuPolicySnapshot policy;
        try {
            policy = verifactuPolicies.required(company.getTaxpayerType());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La empresa no tiene una politica VERI*FACTU utilizable",
                    exception);
        }

        return new ValidatedPairing(
                pairing,
                license,
                licenseReference,
                companyTaxId,
                companyName,
                companyAddress,
                storeCode,
                storeName,
                storeAddress,
                timeZoneId,
                policy);
    }

    private void validateRequestedIdentity(
            LicenseSaasLinkRequest request,
            String companyTaxId,
            String storeCode,
            String timeZoneId) {
        boolean localStoreIdentified = request.storeId() != null
                || (request.storeCode() != null && !request.storeCode().isBlank())
                || (request.taxId() != null && !request.taxId().isBlank());
        if (request.storeCode() != null && !request.storeCode().isBlank()) {
            String requestedStoreCode = requestValue(() -> LicenseProvisioningData.storeCode(
                    request.storeCode()));
            if (!requestedStoreCode.equals(storeCode)) {
                throw conflict("El codigo de tienda no coincide con la licencia");
            }
        }
        if (request.companyName() != null && !request.companyName().isBlank()) {
            requestValue(() -> LicenseProvisioningData.requiredName(
                    request.companyName(), "companyName", 200));
        }
        if (request.taxId() != null
                && !request.taxId().isBlank()
                && !LOCAL_DEMO_TAX_ID.equalsIgnoreCase(request.taxId().trim())) {
            String requestedTaxId = requestValue(() -> SpanishTaxId.validate(request.taxId()));
            if (!requestedTaxId.equals(companyTaxId)) {
                throw conflict("El NIF local no coincide con la licencia");
            }
        }
        if (localStoreIdentified || (request.timeZoneId() != null && !request.timeZoneId().isBlank())) {
            String requestedTimeZone = requestValue(() -> LicenseProvisioningData.timeZoneId(
                    request.timeZoneId()));
            if (!requestedTimeZone.equals(timeZoneId)) {
                throw conflict("La zona horaria local no coincide con la tienda de la licencia");
            }
        }
    }

    private Map<String, String> resolveAddress(
            Map<String, String> configured,
            Map<String, String> requestFallback,
            String configuredField,
            String requestField) {
        if (configured != null) {
            return configurationValue(() -> LicenseProvisioningData.fiscalAddress(
                    configured, configuredField));
        }
        return requestValue(() -> LicenseProvisioningData.fiscalAddress(
                requestFallback, requestField));
    }

    private LicenseSaasLinkResponse response(
            ValidatedPairing validated,
            String token,
            LicenseSaasStatus effectiveStatus) {
        SaasPairingCode pairing = validated.pairing();
        SaasCompany company = pairing.getCompany();
        SaasStore store = pairing.getStore();
        SaasLicense license = validated.license();
        VerifactuPolicySnapshot policy = validated.policy();
        return new LicenseSaasLinkResponse(
                validated.licenseReference(),
                company.getId(),
                store.getId(),
                validated.companyTaxId(),
                validated.companyName(),
                validated.companyAddress(),
                validated.storeCode(),
                validated.storeName(),
                validated.storeAddress(),
                validated.timeZoneId(),
                license.getValidUntil(),
                effectiveStatus,
                license.getMaxWindows(),
                license.getMaxPda(),
                license.getLicenseVersion(),
                validated.companyTaxId(),
                company.getTaxpayerType(),
                company.getTaxRegime(),
                company.getCommercialProfile(),
                policy.activationDate(),
                policy.version(),
                policy.updatedAt(),
                token);
    }

    private static <T> T requestValue(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static <T> T configurationValue(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La configuracion SaaS no permite completar el enlace: "
                            + exception.getMessage(),
                    exception);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ValidatedRequest(
            String pairingCode,
            UUID installationId,
            String installationReference,
            String installationPublicKey) {
    }

    private record ValidatedPairing(
            SaasPairingCode pairing,
            SaasLicense license,
            String licenseReference,
            String companyTaxId,
            String companyName,
            Map<String, String> companyAddress,
            String storeCode,
            String storeName,
            Map<String, String> storeAddress,
            String timeZoneId,
            VerifactuPolicySnapshot policy) {
    }
}
