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
        if (record == null || artifacts.existsById(record.getId())
                || printSnapshots.existsById(record.getId())) {
            return;
        }
        var company = companies.findById(record.getCompanyId())
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(record.getInstallationId())
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var system = new VerifactuSystemInfo(
                producerName, producerTaxId, systemName, systemId,
                record.getApplicationVersion(), installation.getReferencia(),
                true, false, false);
        var unsignedXml = xml.recordXml(new VerifactuXmlBatchRequest(
                company.getRazonSocial(), record.getIssuerTaxId(), List.of(record), system), record);
        var environment = runtime.endpointEnvironment();
        var print = snapshots.create(record, record.getFiscalMode(), environment,
                record.getApplicationVersion());
        printSnapshots.save(new FiscalPrintSnapshotRecord(record.getId(), print, Instant.now()));
        var signedXml = record.getFiscalMode() == FiscalMode.NO_VERIFACTU
                ? signer.sign(record, unsignedXml)
                : null;
        var persistedXml = signedXml == null ? unsignedXml : signedXml;
        artifacts.save(new FiscalRecordArtifact(
                record.getId(), record.getFiscalMode(), environment, runtime.isSandbox(),
                unsignedXml, signedXml, sha256(persistedXml), print, Instant.now()));
    }

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
