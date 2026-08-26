package com.tpverp.backend.verifactu;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the taxpayer identity without consulting mutable company data.
 *
 * <p>V203 froze the identity for new artifacts but deliberately left historical
 * rows null. A legacy ALTA can be recovered from its own immutable XML; an
 * ANULACION can only inherit it through its explicit ANULA relation. Every
 * successful fallback is frozen in a separate append-only table before the
 * record can be sent.</p>
 */
@Service
public class FrozenFiscalIdentityResolver {

    private final LegacyFiscalArtifactIdentityRepository legacyIdentities;
    private final FiscalRecordRepository records;
    private final FiscalRecordRelationRepository relations;
    private final FiscalRecordArtifactRepository artifacts;
    private final VerifactuXmlService xml;
    private final Clock clock;

    public FrozenFiscalIdentityResolver(
            LegacyFiscalArtifactIdentityRepository legacyIdentities,
            FiscalRecordRepository records,
            FiscalRecordRelationRepository relations,
            FiscalRecordArtifactRepository artifacts,
            VerifactuXmlService xml,
            Clock clock) {
        this.legacyIdentities = legacyIdentities;
        this.records = records;
        this.relations = relations;
        this.artifacts = artifacts;
        this.xml = xml;
        this.clock = clock;
    }

    @Transactional
    public FrozenIssuerIdentity resolve(FiscalRecord record, FiscalRecordArtifact artifact) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(artifact, "artifact");
        if (!record.getId().equals(artifact.getRecordId())) {
            throw unresolved("El artefacto no pertenece al registro fiscal");
        }
        boolean hasName = nonBlank(artifact.getIssuerName());
        boolean hasTaxId = nonBlank(artifact.getIssuerTaxId());
        if (hasName != hasTaxId) {
            throw unresolved("La identidad congelada esta incompleta");
        }
        if (hasName) {
            return verified(record, artifact.getIssuerName(), artifact.getIssuerTaxId());
        }
        return legacyIdentities.findById(record.getId())
                .map(identity -> verifyPersistedLegacy(record, artifact, identity))
                .orElseGet(() -> recoverAndFreeze(record, artifact));
    }

    private FrozenIssuerIdentity recoverAndFreeze(
            FiscalRecord record, FiscalRecordArtifact artifact) {
        var recovered = recover(record, artifact);
        var source = source(record);
        legacyIdentities.saveAndFlush(new LegacyFiscalArtifactIdentity(
                record.getId(), recovered.issuerName(), recovered.issuerTaxId(),
                source, Instant.now(clock)));
        return recovered;
    }

    private FrozenIssuerIdentity verifyPersistedLegacy(
            FiscalRecord record,
            FiscalRecordArtifact artifact,
            LegacyFiscalArtifactIdentity persisted) {
        var recovered = recover(record, artifact);
        if (persisted.getSource() != source(record)
                || !persisted.getIssuerName().equals(recovered.issuerName())
                || !persisted.getIssuerTaxId().equals(recovered.issuerTaxId())) {
            throw unresolved(
                    "La identidad legacy persistida no coincide con su evidencia inmutable");
        }
        return recovered;
    }

    private FrozenIssuerIdentity recover(
            FiscalRecord record, FiscalRecordArtifact artifact) {
        return record.getOperation() == FiscalRecordOperation.ALTA
                ? fromAltaXml(record, artifact)
                : fromRelatedAlta(record);
    }

    private static LegacyFiscalIdentitySource source(FiscalRecord record) {
        return record.getOperation() == FiscalRecordOperation.ALTA
                ? LegacyFiscalIdentitySource.REGISTRO_ALTA_XML
                : LegacyFiscalIdentitySource.ALTA_RELACIONADA;
    }

    private FrozenIssuerIdentity fromAltaXml(
            FiscalRecord record, FiscalRecordArtifact artifact) {
        final VerifactuXmlService.FrozenIssuerIdentity recovered;
        try {
            recovered = xml.frozenAltaIssuerIdentity(artifact.getUnsignedXml());
        } catch (IllegalArgumentException exception) {
            throw unresolved("El XML de alta no contiene una identidad inequivoca", exception);
        }
        return verified(record, recovered.issuerName(), recovered.issuerTaxId());
    }

    private FrozenIssuerIdentity fromRelatedAlta(FiscalRecord cancellation) {
        var relation = relations.findByRecordIdAndType(
                        cancellation.getId(), FiscalRelationType.ANULA)
                .orElseThrow(() -> unresolved(
                        "La anulacion legacy no tiene una relacion ANULA inequivoca"));
        var original = records.findById(relation.getRelatedId())
                .orElseThrow(() -> unresolved("No existe el alta relacionada"));
        if (original.getOperation() != FiscalRecordOperation.ALTA
                || !original.chainId().equals(cancellation.chainId())
                || !original.getCompanyId().equals(cancellation.getCompanyId())
                || !original.getInstallationId().equals(cancellation.getInstallationId())
                || !original.getIssuerTaxId().equals(cancellation.getIssuerTaxId())
                || !original.getNumber().equals(cancellation.getNumber())
                || !original.getIssueDate().equals(cancellation.getIssueDate())) {
            throw unresolved("La relacion ANULA no identifica el alta fiscal original");
        }
        var originalArtifact = artifacts.findByRecordId(original.getId())
                .orElseThrow(() -> unresolved(
                        "El alta relacionada no tiene artefacto fiscal congelado"));
        return resolve(original, originalArtifact);
    }

    private static FrozenIssuerIdentity verified(
            FiscalRecord record, String issuerName, String issuerTaxId) {
        if (!nonBlank(issuerName) || !record.getIssuerTaxId().equals(issuerTaxId)) {
            throw unresolved("La identidad recuperada no coincide con el registro fiscal");
        }
        return new FrozenIssuerIdentity(issuerName.trim(), issuerTaxId);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static UnresolvedLegacyFiscalIdentityException unresolved(String detail) {
        return new UnresolvedLegacyFiscalIdentityException(detail);
    }

    private static UnresolvedLegacyFiscalIdentityException unresolved(
            String detail, Exception cause) {
        return new UnresolvedLegacyFiscalIdentityException(detail, cause);
    }

    public record FrozenIssuerIdentity(String issuerName, String issuerTaxId) {
    }
}
