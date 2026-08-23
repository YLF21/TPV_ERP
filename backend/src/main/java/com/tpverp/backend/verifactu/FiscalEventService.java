package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.CompanyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalEventService {
    private final CompanyRepository companies;
    private final InstallationRepository installations;
    private final FiscalEventChainRepository chains;
    private final FiscalEventRepository events;
    private final FiscalEventXmlService xml;
    private final FiscalXadesSigner signer;
    private final String producerName;
    private final String producerTaxId;
    private final String systemName;
    private final String systemId;
    private final String systemVersion;

    public FiscalEventService(CompanyRepository companies, InstallationRepository installations,
            FiscalEventChainRepository chains, FiscalEventRepository events,
            FiscalEventXmlService xml, FiscalXadesSigner signer,
            @Value("${tpv.verifactu.producer-name:TPV ERP DEV}") String producerName,
            @Value("${tpv.verifactu.producer-tax-id:B00000000}") String producerTaxId,
            @Value("${tpv.verifactu.system-name:TPV ERP}") String systemName,
            @Value("${tpv.verifactu.system-id:TPVERP}") String systemId,
            @Value("${tpv.verifactu.system-version:4.1.0}") String systemVersion) {
        this.companies = companies;
        this.installations = installations;
        this.chains = chains;
        this.events = events;
        this.xml = xml;
        this.signer = signer;
        this.producerName = producerName;
        this.producerTaxId = producerTaxId;
        this.systemName = systemName;
        this.systemId = systemId;
        this.systemVersion = systemVersion;
    }

    /** Creates and signs one official RegistroEvento in the append-only event chain. */
    @Transactional
    public FiscalEvent create(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail) {
        return createAt(companyId, installationId, mode, type, detail, Instant.now());
    }

    /** Emits one summary only after six elapsed operating hours since the last event. */
    @Transactional
    public FiscalEvent createSummaryIfDue(UUID companyId, UUID installationId, FiscalMode mode,
            Instant now) {
        if (mode != FiscalMode.NO_VERIFACTU) {
            return null;
        }
        var latest = events.findTopByCompanyIdAndInstallationIdOrderBySequenceDesc(
                companyId, installationId).orElse(null);
        if (latest == null || now.isBefore(latest.getGeneratedAt().plus(Duration.ofHours(6)))) {
            return null;
        }
        return createAt(companyId, installationId, mode, FiscalEventType.SUMMARY, null, now);
    }

    private FiscalEvent createAt(UUID companyId, UUID installationId, FiscalMode mode,
            FiscalEventType type, String detail, Instant now) {
        if (mode != FiscalMode.NO_VERIFACTU) {
            return null; // VERI*FACTU does not generate the mandatory event log.
        }
        var company = companies.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("Empresa fiscal no encontrada"));
        var installation = installations.findById(installationId)
                .orElseThrow(() -> new IllegalStateException("Instalacion fiscal no encontrada"));
        var offset = now.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        var system = new VerifactuSystemInfo(producerName, producerTaxId, systemName, systemId,
                systemVersion, installation.getReferencia(), false, true, false);
        chains.insertIfMissing(UUID.randomUUID(), companyId, installationId, now);
        var chain = chains.findForUpdate(companyId, installationId)
                .orElseThrow(() -> new IllegalStateException("Cadena de eventos no encontrada"));
        var previousHash = chain.previousHash();
        var sequence = chain.nextSequence();
        var hash = new OfficialHashService().hash(new FiscalEventHashInput(
                producerTaxId, "", systemId, systemVersion, installation.getReferencia(),
                company.getTaxId(), type.code(), previousHash, offset));
        var normalizedDetail = detail == null ? null : detail.trim();
        if (normalizedDetail != null && normalizedDetail.length() > 100) {
            throw new IllegalArgumentException("OtrosDatosEvento no puede superar 100 caracteres");
        }
        var unsignedXml = xml.unsignedXml(system, company.getRazonSocial(), company.getTaxId(),
                type, normalizedDetail, offset, previousHash, hash);
        var signedXml = signer.signEvent(companyId, installationId, unsignedXml);
        var event = new FiscalEvent(companyId, installationId, sequence, type, mode, now,
                previousHash, hash, unsignedXml, signedXml, sha256(signedXml), now);
        events.save(event);
        chain.advance(sequence, hash, now);
        chains.save(chain);
        return event;
    }

    @Transactional(readOnly = true)
    public List<FiscalEvent> findTop50(UUID companyId) {
        return events.findTop50ByCompanyIdOrderByGeneratedAtDesc(companyId);
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
