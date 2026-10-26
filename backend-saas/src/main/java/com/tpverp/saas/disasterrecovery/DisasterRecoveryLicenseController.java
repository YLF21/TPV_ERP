package com.tpverp.saas.disasterrecovery;

import com.tpverp.saas.license.*;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** An authenticated, complete snapshot, never a request to activate or re-link an installation. */
@RestController
public class DisasterRecoveryLicenseController {
    private final DisasterRecoveryService recovery;
    private final VerifactuActivationPolicyResolver policies;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DisasterRecoveryLicenseController(DisasterRecoveryService recovery,
            VerifactuActivationPolicyResolver policies, JdbcTemplate jdbc, Clock clock) {
        this.recovery=recovery;this.policies=policies;this.jdbc=jdbc;this.clock=clock;
    }

    @PostMapping("/api/v1/backup-recoveries/{id}/license")
    @Transactional
    public Snapshot license(@PathVariable UUID id, @Valid @RequestBody DisasterRecoveryModels.Proof proof,
            @RequestHeader(name="X-TPV-Installation-Token",required=false) String token) {
        var installation=recovery.requireActivatedLicenseCredential(id,proof,token);
        var license=installation.getLicense();var company=license.getCompany();var store=installation.getStore();
        var policy=policies.required(company.getTaxpayerType());
        var grant=jdbc.queryForMap("select backup_id,backup_sha256,destination_key_fingerprint from saas_disaster_recovery where id=?",id);
        String publicKey=jdbc.queryForObject("select installation_public_key from saas_installation where id=?",String.class,installation.getId());
        var status=license.getStatus()==LicenseSaasStatus.VALIDA && !clock.instant().isBefore(license.getValidUntil())
                ? LicenseSaasStatus.CADUCADA:license.getStatus();
        return new Snapshot(id,installation.getInstallationId(),(UUID)grant.get("backup_id"),
                (String)grant.get("backup_sha256"),(String)grant.get("destination_key_fingerprint"),
                installation.getInstallationReference(),publicKey,company.getId(),store.getId(),license.getReference(),
                company.getTaxId(),company.getTaxpayerType(),store.getTaxRegime(),store.getCommercialProfile(),
                installation.getLinkedAt(),license.getValidUntil(),status,license.getMaxWindows(),license.getMaxPda(),
                license.getLicenseVersion(),policy.activationDate(),policy.version(),policy.updatedAt(),store.getInternalCode());
    }

    public record Snapshot(UUID recoveryId,UUID installationId,UUID backupId,String backupSha256,
            String destinationKeyFingerprint,String installationReference,String installationPublicKey,
            UUID saasCompanyId,UUID saasStoreId,String licenseReference,String taxId,TaxpayerType taxpayerType,
            TaxRegime taxRegime,CommercialProfile commercialProfile,Instant linkedAt,Instant validUntil,
            LicenseSaasStatus status,int maxWindows,int maxPda,long licenseVersion,LocalDate verifactuActivationDate,
            long verifactuPolicyVersion,Instant verifactuPolicyUpdatedAt,String storeInternalCode) { }
}
