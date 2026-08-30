package com.tpverp.backend.verifactu;

import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.installation.InstallationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the frozen XML/QR evidence in the same transaction as the record. */
@Service
public class FiscalArtifactService {

    private final FiscalRecordArtifactRepository artifacts;
    private final FiscalSystemVersionRepository systemVersions;
    private final FiscalPrintSnapshotRecordRepository printSnapshots;
    private final CompanyRepository companies;
    private final InstallationRepository installations;
    private final VerifactuXmlService xml;
    private final FiscalQrUrlService qrUrls;
    private final FiscalPrintSnapshotFactory snapshots;
    private final FiscalRuntimeProperties runtime;
    private final FiscalXadesSigner signer;
    private final String producerName;
    private final String producerTaxId;
    private final String systemName;
    private final String systemId;

    public FiscalArtifactService(
            FiscalRecordArtifactRepository artifacts,
            FiscalSystemVersionRepository systemVersions,
            FiscalPrintSnapshotRecordRepository printSnapshots,
            CompanyRepository companies,
            InstallationRepository installations,
            VerifactuXmlService xml,
            FiscalQrUrlService qrUrls,
            FiscalPrintSnapshotFactory snapshots,
            FiscalRuntimeProperties runtime,
            FiscalXadesSigner signer,
            @Value("${tpv.verifactu.producer-name:TPV ERP DEV}") String producerName,
            @Value("${tpv.verifactu.producer-tax-id:B00000000}") String producerTaxId,
            @Value("${tpv.verifactu.system-name:TPV ERP}") String systemName,
            @Value("${tpv.verifactu.system-id:TPVERP}") String systemId) {
        this.artifacts = artifacts;
        this.systemVersions = systemVersions;
        this.printSnapshots = printSnapshots;
        this.companies = companies;
        this.installations = installations;
        this.xml = xml;
        this.qrUrls = qrUrls;
        this.snapshots = snapshots;
        this.runtime = runtime;
        this.signer = signer;
        this.producerName = producerName;
        this.producerTaxId = producerTaxId;
        this.systemName = systemName;
        this.systemId = systemId;
    }

    @Transactional
    public void create(FiscalRecord record) {
        if (record == null || record.getFiscalMode() == FiscalMode.PRE_SIF
                || artifacts.existsById(record.getId())
                || printSnapshots.existsById(record.getId())) {
            return;
        }
        if (runtime.systemVersion() == null
                || !runtime.systemVersion().equals(record.getApplicationVersion())) {
            throw new IllegalStateException(
                    "La version de aplicacion del registro no coincide con la version SIF activa");
        }
        var company = companies.findById(record.getCompanyId())
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(record.getInstallationId())
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var system = new VerifactuSystemInfo(
                producerName, producerTaxId, systemName, systemId,
                record.getApplicationVersion(), installation.getReferencia(),
                onlyVerifactu(), false, false);
        var systemVersion = systemVersions
                .findByCompanyIdAndInstallationIdAndSystemVersionAndInstallationNumberAndReleaseId(
                        record.getCompanyId(), record.getInstallationId(),
                        record.getApplicationVersion(), installation.getReferencia(), releaseId())
                .map(existing -> {
                    if (!existing.matches(producerTaxId, producerName, systemName, systemId,
                            record.getApplicationVersion(), installation.getReferencia(),
                            runtime.declarationHash(),
                            runtime.isSandbox())) {
                        throw new IllegalStateException(
                                "La identidad fiscal no coincide con la version SIF congelada");
                    }
                    requireReleaseMatch(existing);
                    return existing;
                })
                .orElseGet(() -> systemVersions.save(new FiscalSystemVersion(
                        record.getCompanyId(), record.getInstallationId(), producerTaxId,
                        producerName, systemName, systemId, record.getApplicationVersion(),
                        installation.getReferencia(), runtime.declarationHash(),
                        runtime.isSandbox(), Instant.now(), releaseId(), artifactHash(), commitHash(),
                        capability(), schemaVersion(), manifestHash())));
        var unsignedXml = xml.recordXml(new VerifactuXmlBatchRequest(
                company.getRazonSocial(), record.getIssuerTaxId(), List.of(record), system), record);
        var environment = runtime.endpointEnvironment();
        // RegistroAnulacion is an AEAT chain record, not a newly issued invoice.
        // It therefore has XML evidence but no invoice-validation QR or print snapshot.
        FiscalPrintSnapshot print = null;
        if (record.getOperation() == FiscalRecordOperation.ALTA) {
            print = snapshots.create(record, record.getFiscalMode(), environment,
                    record.getApplicationVersion());
            printSnapshots.save(new FiscalPrintSnapshotRecord(
                    record.getId(), print, Instant.now()));
        }
        var signedXml = record.getFiscalMode() == FiscalMode.NO_VERIFACTU
                ? signer.sign(record, unsignedXml)
                : null;
        var certificateFingerprint = signedXml == null
                ? null : signer.embeddedCertificateFingerprint(signedXml);
        if (signedXml != null && (certificateFingerprint == null || certificateFingerprint.isBlank())) {
            throw new IllegalStateException("La firma XAdES no contiene una huella de certificado valida");
        }
        var persistedXml = signedXml == null ? unsignedXml : signedXml;
        artifacts.save(new FiscalRecordArtifact(
                record.getId(), record.getFiscalMode(), environment, runtime.isSandbox(),
                systemVersion.getId(), company.getRazonSocial(), record.getIssuerTaxId(),
                company.getDomicilioFiscal(),
                unsignedXml, signedXml, certificateFingerprint, sha256(persistedXml), print, Instant.now()));
    }

    private boolean onlyVerifactu() {
        return capability() == FiscalProductCapability.VERIFACTU_ONLY;
    }

    private FiscalProductCapability capability() {
        var value = runtime.productCapability();
        return value == null ? FiscalProductCapability.DUAL : value;
    }

    private FiscalReleaseManifest manifest() {
        return runtime.releaseManifest();
    }

    private void requireReleaseMatch(FiscalSystemVersion existing) {
        if (manifest() != null
                && !existing.matchesRelease(manifest(), artifactHash())) {
            throw new IllegalStateException("La identidad de release no coincide con la version SIF congelada");
        }
    }

    private String releaseId() { return manifest() == null ? "LEGACY-RUNTIME" : manifest().releaseId(); }
    private String artifactHash() { return runtime.resolvedArtifactHash(); }
    private String commitHash() { return manifest() == null ? null : manifest().commitHash(); }
    private String schemaVersion() { return manifest() == null ? "V216" : manifest().schemaVersion(); }
    private String manifestHash() { return manifest() == null ? null : manifest().manifestHash(); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
