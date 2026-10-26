package com.tpverp.backend.licensing;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.organization.StoreRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recovery-only refresh: an unauthenticated row is never promoted to a generally refreshable cache. */
@Service
public class LicenseRecoveryReconciler {
    private final LicenseRepository licenses;
    private final InstallationRepository installations;
    private final StoreRepository stores;
    private final LicenseSaasCacheAuthenticator authenticator;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public LicenseRecoveryReconciler(LicenseRepository licenses,InstallationRepository installations,
            StoreRepository stores,LicenseSaasCacheAuthenticator authenticator,Clock clock,JdbcTemplate jdbc) {
        this.licenses=licenses;this.installations=installations;this.stores=stores;
        this.authenticator=authenticator;this.clock=clock;this.jdbc=jdbc;
    }

    @Transactional
    public void reconcile(RecoveredLicenseSnapshot authority) {
        require(authority!=null && authority.installationId()!=null && authority.recoveryId()!=null && authority.backupId()!=null,
                "Falta la identidad SaaS recuperada");
        // Hold the recovery binding through the license write, not only during the earlier network request.
        var recovery=jdbc.queryForMap("select state,installation_id,backup_id,backup_sha256 from disaster_recovery where id=? for share",authority.recoveryId());
        require(Set.of("ACTIVE","COMPLETE").contains(recovery.get("state"))
                && authority.installationId().equals(recovery.get("installation_id"))
                && authority.backupId().equals(recovery.get("backup_id"))
                && authority.backupSha256()!=null && authority.backupSha256().matches("[0-9a-f]{64}")
                && authority.backupSha256().equals(recovery.get("backup_sha256")),"La licencia no pertenece a esta recuperacion activada");
        var installation=installations.findById(authority.installationId()).orElseThrow(()->invalid("Instalacion recuperada inexistente"));
        var active=licenses.findByInstalacion_IdAndActivaTrue(installation.getId());
        require(active.size()==1,"La recuperacion requiere una unica licencia activa identificada");
        var license=licenses.findByIdForSaasValidationForUpdate(active.getFirst().getId()).orElseThrow(()->invalid("Licencia recuperada inexistente"));
        var store=stores.findWithCompanyById(license.getTiendaId()).orElseThrow(()->invalid("Tienda recuperada inexistente"));
        require(license.isActiva() && license.isSaasLinked() && license.getInstalacionId().equals(installation.getId())
                && Objects.equals(installation.getReferencia(),authority.installationReference())
                && Objects.equals(installation.getPublicKey(),authority.installationPublicKey())
                && authority.saasCompanyId()!=null && authority.saasStoreId()!=null
                && authority.saasCompanyId().equals(license.getSaasCompanyId())
                && authority.saasStoreId().equals(license.getSaasStoreId())
                && Objects.equals(license.getReferencia(),authority.licenseReference())
                && authority.taxId()!=null && authority.taxId().equals(license.getTaxId())
                && authority.taxId().equals(store.getEmpresa().getTaxId())
                && authority.taxpayerType()!=null && authority.taxpayerType()==license.getTaxpayerType()
                && authority.taxRegime()!=null && authority.taxRegime()==license.getRegimenImpuesto(),
                "La identidad fiscal de la licencia recuperada difiere de SaaS");
        var now=clock.instant();
        require(authority.status()==LicenseSaasStatus.VALIDA && authority.linkedAt()!=null
                && !authority.linkedAt().isAfter(now) && authority.validUntil()!=null && authority.validUntil().isAfter(now)
                && authority.commercialProfile()!=null && authority.maxWindows()>0 && authority.maxPda()>=0
                && authority.licenseVersion()>0 && authority.verifactuActivationDate()!=null
                && authority.verifactuPolicyVersion()>=0 && authority.verifactuPolicyUpdatedAt()!=null,
                "SaaS no confirma una licencia vigente y completa para la recuperacion");
        // Never postpone a fiscal policy already reached locally. Conflicts require support, not a cache bypass.
        var localDate=license.getVerifactuActivationDate();
        var today=now.atZone(ZoneId.of(store.getTimezone())).toLocalDate();
        require(localDate==null || today.isBefore(localDate) || !authority.verifactuActivationDate().isAfter(localDate),
                "La politica SaaS pospone una activacion fiscal ya alcanzada");
        license.applyRecoveredSaasSnapshot(authority,now);
        LicenseSaasStorePresentation.rememberCode(store,license,authority.saasCompanyId(),authority.saasStoreId(),
                authority.licenseReference(),authority.storeInternalCode());
        authenticator.seal(license);
        require(authenticator.isAuthentic(license) && license.isOperationalAt(now),"La licencia recuperada no quedo autentica y operativa");
        licenses.save(license);
    }

    private static void require(boolean condition,String message) {if(!condition)throw invalid(message);}
    private static LicenseValidationException invalid(String message) {return new LicenseValidationException(message);}
}
