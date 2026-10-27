package com.tpverp.backend.licensing;

import com.tpverp.backend.licensing.application.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Complete authority response obtained using both an activated recovery key and its current credential. */
public record RecoveredLicenseSnapshot(UUID recoveryId,UUID installationId,UUID backupId,String backupSha256,
        String destinationKeyFingerprint,String installationReference,String installationPublicKey,
        UUID saasCompanyId,UUID saasStoreId,String licenseReference,String taxId,TaxpayerType taxpayerType,
        TaxRegime taxRegime,CommercialProfile commercialProfile,Instant linkedAt,Instant validUntil,
        LicenseSaasStatus status,int maxWindows,int maxPda,long licenseVersion,LocalDate verifactuActivationDate,
        long verifactuPolicyVersion,Instant verifactuPolicyUpdatedAt,String storeInternalCode) { }
