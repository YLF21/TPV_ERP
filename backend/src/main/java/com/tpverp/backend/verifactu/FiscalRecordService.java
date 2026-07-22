package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalRecordService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private final FiscalChainRepository chains;
    private final FiscalRecordRepository records;
    private final FiscalRecordRelationRepository relations;
    private final FiscalSubmissionStateRepository states;
    private final VerifactuConfigurationRepository configurations;
    private final LicenseRepository licenses;
    private final CompanyRepository companies;
    private final StoreRepository stores;
    private final InstallationRepository installations;
    private final CommercialDocumentRepository documents;
    private final CustomerRepository customers;
    private final VerifactuActivationService activation;
    private final FiscalSnapshotFactory snapshots;
    private final FiscalDocumentPolicy policy;
    private final Clock clock;
    private final OfficialHashService officialHashes = new OfficialHashService();
    private final FiscalJsonHasher jsonHasher = new FiscalJsonHasher();

    public FiscalRecordService(
            FiscalChainRepository chains,
            FiscalRecordRepository records,
            FiscalRecordRelationRepository relations,
            FiscalSubmissionStateRepository states,
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            CompanyRepository companies,
            StoreRepository stores,
            InstallationRepository installations,
            CommercialDocumentRepository documents,
            CustomerRepository customers,
            VerifactuActivationService activation,
            FiscalSnapshotFactory snapshots,
            FiscalDocumentPolicy policy,
            Clock clock) {
        this.chains = chains;
        this.records = records;
        this.relations = relations;
        this.states = states;
        this.configurations = configurations;
        this.licenses = licenses;
        this.companies = companies;
        this.stores = stores;
        this.installations = installations;
        this.documents = documents;
        this.customers = customers;
        this.activation = activation;
        this.snapshots = snapshots;
        this.policy = policy;
        this.clock = clock;
    }

    // Records and chains a fiscal operation using only persisted and validated data.
    @Transactional(noRollbackFor = VerifactuInactiveException.class)
    public FiscalRecord register(FiscalRecordCommand command) {
        if (command != null
                && command.operation() == FiscalRecordOperation.ALTA
                && isRectification(command.documentType())) {
            throw new IllegalArgumentException(
                    "Una factura rectificativa requiere origen y metodo de rectificacion");
        }
        return register(command, null, null, null);
    }

    @Transactional(noRollbackFor = VerifactuInactiveException.class)
    public FiscalRecord registerSubstitution(
            FiscalRecordCommand command, UUID substitutedDocumentId) {
        if (command == null
                || command.operation() != FiscalRecordOperation.ALTA
                || command.documentType() != FiscalDocumentType.F3) {
            throw new IllegalArgumentException("message.fiscal_record.substitution_requires_f3");
        }
        return register(command, substitutedDocumentId, FiscalRelationType.SUSTITUYE, null);
    }
    // Creates the F3 registration and links it to the replaced simplified invoice.

    @Transactional(noRollbackFor = VerifactuInactiveException.class)
    public FiscalRecord registerRectification(
            FiscalRecordCommand command,
            UUID rectifiedDocumentId,
            FiscalRectificationMethod method) {
        if (command == null
                || command.operation() != FiscalRecordOperation.ALTA
                || !isRectification(command.documentType())) {
            throw new IllegalArgumentException(
                    "El registro de rectificacion requiere un alta R1, R2, R3, R4 o R5");
        }
        return register(command, Objects.requireNonNull(rectifiedDocumentId, "rectifiedDocumentId"),
                FiscalRelationType.RECTIFICA, Objects.requireNonNull(method, "method"));
    }

    // Creates a correction registration without changing the original identity or economic content.
    @Transactional
    public FiscalRecord registerCorrection(
            FiscalRecord original, Map<String, Object> correctedSnapshot) {
        if (original == null || original.getOperation() != FiscalRecordOperation.ALTA) {
            throw new IllegalArgumentException("Solo puede subsanarse un registro de alta");
        }
        validateCorrectionEconomics(original, correctedSnapshot);
        var generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        var chain = chains.findForUpdate(original.getCompanyId(), original.getInstallationId())
                .orElseThrow(() -> new IllegalStateException("Cadena fiscal no encontrada"));
        if (!original.chainId().equals(chain.getId())) {
            throw new IllegalStateException("El registro no pertenece a la cadena fiscal activa");
        }
        var snapshot = new LinkedHashMap<>(correctedSnapshot);
        addPreviousRecord(snapshot, chain.getLastRecord());
        var previousHash = chain.previousHash();
        var generatedOffset = generatedAt.atZone(ZoneId.of(original.getTimezone()))
                .toOffsetDateTime();
        var hash = officialHashes.hash(new AltaHashInput(
                original.getIssuerTaxId(), original.getNumber(),
                DATE.format(original.getIssueDate()), original.getDocumentType().name(),
                original.getTotalTax(), original.getTotalAmount(), previousHash,
                generatedOffset));
        var correction = records.save(new FiscalRecord(
                chain.getId(), original.getCompanyId(), original.getInstallationId(),
                original.getStoreId(), original.getDocumentId(), chain.nextSequence(),
                FiscalRecordOperation.ALTA, original.getDocumentType(), original.getNumber(),
                original.getIssueDate(), generatedAt, original.getTimezone(),
                original.getIssuerTaxId(), original.getTotalTax(), original.getTotalAmount(),
                previousHash, hash, jsonHasher.hash(snapshot), snapshot,
                original.getFormatVersion(), original.getAlgorithmVersion(),
                original.getApplicationVersion()));
        states.save(new FiscalSubmissionState(
                correction.getId(), FiscalSubmissionStatus.PENDIENTE, generatedAt));
        relations.save(new FiscalRecordRelation(
                chain.getId(), correction.getId(), original.getId(), FiscalRelationType.SUBSANA));
        chain.advance(correction, generatedAt);
        return correction;
    }

    private FiscalRecord register(
            FiscalRecordCommand command,
            UUID relatedDocumentId,
            FiscalRelationType relationType,
            FiscalRectificationMethod rectificationMethod) {
        if (command == null) {
            throw new IllegalArgumentException("command es obligatorio");
        }
        var generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        var context = fiscalContext(command, generatedAt);
        policy.validate(context.document(), command.operation(), command.documentType());

        // The UPSERT and lock serialize uniqueness and chain advancement.
        chains.insertIfMissing(
                UUID.randomUUID(), command.companyId(), command.installationId(), generatedAt);
        var chain = chains.findForUpdate(command.companyId(), command.installationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la cadena fiscal"));
        if (records.findByDocumentIdAndOperation(
                command.documentId(), command.operation()).isPresent()) {
            throw new IllegalStateException(
                    "La operacion fiscal ya esta registrada para el documento");
        }
        var original = originalForCancellation(command, chain);
        var related = relatedRecord(command, chain, relatedDocumentId, relationType);
        var snapshot = new LinkedHashMap<>(snapshots.create(
                context.document(), context.issuerTaxId(), command.operation(),
                command.documentType(), context.customer()));
        if (relationType == FiscalRelationType.RECTIFICA) {
            snapshot.put("tipoRectificativa", rectificationMethod.name());
        }
        addPreviousRecord(snapshot, chain.getLastRecord());
        addRelatedDocument(snapshot, related, relationType);
        var previousHash = chain.previousHash();
        var totalTax = amount(command.operation(), context.document().getImpuestoTotal());
        var totalAmount = amount(command.operation(), context.document().getTotal());
        var record = new FiscalRecord(
                chain.getId(), command.companyId(), command.installationId(),
                command.storeId(), command.documentId(), chain.nextSequence(),
                command.operation(), command.documentType(),
                context.document().getNumero(), context.document().getFecha(),
                generatedAt, context.timezone(), context.issuerTaxId(),
                totalTax, totalAmount, previousHash,
                officialHash(command, context, totalTax, totalAmount, previousHash),
                jsonHasher.hash(snapshot), snapshot, command.formatVersion(),
                command.algorithmVersion(), command.applicationVersion());

        var saved = records.save(record);
        states.save(new FiscalSubmissionState(
                saved.getId(), FiscalSubmissionStatus.PENDIENTE, generatedAt));
        if (original != null) {
            relations.save(new FiscalRecordRelation(
                    chain.getId(), saved.getId(), original.getId(),
                    FiscalRelationType.ANULA));
        }
        if (related != null) {
            relations.save(new FiscalRecordRelation(
                    chain.getId(), saved.getId(), related.getId(), relationType));
        }
        chain.advance(saved, generatedAt);
        return saved;
    }

    private FiscalRecord relatedRecord(
            FiscalRecordCommand command,
            FiscalChain chain,
            UUID relatedDocumentId,
            FiscalRelationType relationType) {
        if (relatedDocumentId == null) {
            return null;
        }
        var related = records.findByDocumentIdAndOperation(
                        relatedDocumentId, FiscalRecordOperation.ALTA)
                .orElseThrow(() -> new IllegalStateException(
                        "El documento rectificado necesita un alta fiscal previa"));
        if (relationType == FiscalRelationType.SUSTITUYE) {
            if (related.getDocumentType() != FiscalDocumentType.F2) {
                throw new IllegalArgumentException(
                        "Solo una factura simplificada F2 puede sustituirse por F3");
            }
        } else if (relationType == FiscalRelationType.RECTIFICA) {
            validateRectifiedType(command.documentType(), related.getDocumentType());
        } else {
            throw new IllegalArgumentException("Relacion fiscal de alta no admitida");
        }
        if (!related.chainId().equals(chain.getId())
                || !related.getCompanyId().equals(command.companyId())
                || !related.getStoreId().equals(command.storeId())) {
            throw new IllegalStateException(
                    "El ticket sustituido no pertenece a la cadena fiscal activa");
        }
        return related;
    }

    private static void addRelatedDocument(
            Map<String, Object> snapshot,
            FiscalRecord related,
            FiscalRelationType relationType) {
        if (related == null) {
            return;
        }
        var key = relationType == FiscalRelationType.SUSTITUYE
                ? "facturasSustituidas"
                : relationType == FiscalRelationType.RECTIFICA
                    ? "facturasRectificadas"
                    : null;
        if (key == null) {
            return;
        }
        snapshot.put(key, List.of(Map.of(
                "nifEmisor", related.getIssuerTaxId(),
                "numero", related.getNumber(),
                "fecha", related.getIssueDate().toString())));
    }

    private static void validateRectifiedType(
            FiscalDocumentType rectification,
            FiscalDocumentType original) {
        if (rectification == FiscalDocumentType.R5) {
            if (original != FiscalDocumentType.F2 && original != FiscalDocumentType.R5) {
                throw new IllegalArgumentException(
                        "R5 solo puede rectificar una factura simplificada F2 o R5");
            }
            return;
        }
        if (original != FiscalDocumentType.F1
                && original != FiscalDocumentType.F3
                && original != FiscalDocumentType.R1
                && original != FiscalDocumentType.R2
                && original != FiscalDocumentType.R3
                && original != FiscalDocumentType.R4) {
            throw new IllegalArgumentException(
                    "R1-R4 solo pueden rectificar una factura completa");
        }
    }

    private static boolean isRectification(FiscalDocumentType type) {
        return type == FiscalDocumentType.R1
                || type == FiscalDocumentType.R2
                || type == FiscalDocumentType.R3
                || type == FiscalDocumentType.R4
                || type == FiscalDocumentType.R5;
    }

    private static void addPreviousRecord(
            Map<String, Object> snapshot, FiscalRecord previous) {
        if (previous == null) {
            return;
        }
        snapshot.put("registroAnterior", Map.of(
                "nifEmisor", previous.getIssuerTaxId(),
                "numero", previous.getNumber(),
                "fecha", previous.getIssueDate().toString(),
                "huella", previous.getHash()));
    }
    // Congela la identidad completa exigida por RegistroAnterior en el XML.

    private FiscalContext fiscalContext(
            FiscalRecordCommand command, Instant generatedAt) {
        var company = companies.findById(command.companyId())
                .orElseThrow(() -> invalid("empresa"));
        var store = stores.findById(command.storeId())
                .orElseThrow(() -> invalid("tienda"));
        if (!store.getEmpresa().getId().equals(company.getId())) {
            throw new IllegalArgumentException("La tienda no pertenece a la empresa");
        }
        installations.findById(command.installationId())
                .orElseThrow(() -> invalid("instalacion"));
        var document = documents.findById(command.documentId())
                .orElseThrow(() -> invalid("documento"));
        if (!document.getTiendaId().equals(store.getId())) {
            throw new IllegalArgumentException("El documento no pertenece a la tienda");
        }
        var license = licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                        command.storeId(), command.installationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe una licencia activa para la tienda e instalacion"));
        if (!license.isOperationalAt(generatedAt)) {
            throw new IllegalStateException("La licencia no esta vigente");
        }
        var companyTaxId = SpanishTaxId.validate(company.getTaxId());
        var licenseTaxId = SpanishTaxId.validate(license.getTaxId());
        if (!licenseTaxId.equals(companyTaxId)) {
            throw new IllegalStateException(
                    "El NIF de la licencia no coincide con la empresa");
        }
        var customer = customer(document, command.companyId());
        configurations.insertIfMissing(UUID.randomUUID(), command.companyId());
        var configuration = configurations.findByCompanyId(command.companyId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la configuracion VERI*FACTU"));
        var zone = ZoneId.of(store.getTimezone());
        if (!activation.isActive(
                configuration,
                license.getTaxpayerType(),
                license.getVerifactuActivationDate(),
                generatedAt,
                zone)) {
            throw new VerifactuInactiveException();
        }
        return new FiscalContext(
                companyTaxId, zone.getId(),
                generatedAt.atZone(zone).toOffsetDateTime(), document, customer);
    }

    private Customer customer(CommercialDocument document, UUID companyId) {
        if (document.getClienteId() == null) {
            return null;
        }
        return customers.findByIdAndCompanyId(document.getClienteId(), companyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No existe el cliente del documento para la empresa"));
    }

    private FiscalRecord originalForCancellation(
            FiscalRecordCommand command, FiscalChain chain) {
        if (command.operation() != FiscalRecordOperation.ANULACION) {
            return null;
        }
        var original = records.findByDocumentIdAndOperation(
                        command.documentId(), FiscalRecordOperation.ALTA)
                .orElseThrow(() -> new IllegalStateException(
                        "La anulacion requiere un alta fiscal previa"));
        if (original.getDocumentType() != command.documentType()) {
            throw new IllegalArgumentException(
                    "La anulacion debe conservar el tipo fiscal del alta original");
        }
        if (!original.chainId().equals(chain.getId())) {
            throw new IllegalStateException(
                    "El alta original no pertenece a la cadena fiscal activa");
        }
        return original;
    }

    private String officialHash(
            FiscalRecordCommand command,
            FiscalContext context,
            BigDecimal totalTax,
            BigDecimal totalAmount,
            String previousHash) {
        var document = context.document();
        var issueDate = DATE.format(document.getFecha());
        if (command.operation() == FiscalRecordOperation.ALTA) {
            return officialHashes.hash(new AltaHashInput(
                    context.issuerTaxId(), document.getNumero(), issueDate,
                    command.documentType().name(), totalTax, totalAmount,
                    previousHash, context.generatedAt()));
        }
        return officialHashes.hash(new CancellationHashInput(
                context.issuerTaxId(), document.getNumero(), issueDate,
                previousHash, context.generatedAt()));
    }

    private static IllegalArgumentException invalid(String entity) {
        return new IllegalArgumentException(
                "No existe " + entity + " para el registro fiscal");
    }

    private static BigDecimal amount(
            FiscalRecordOperation operation, BigDecimal value) {
        return operation == FiscalRecordOperation.ALTA ? value : null;
    }

    private static void validateCorrectionEconomics(
            FiscalRecord original, Map<String, Object> snapshot) {
        if (snapshot == null
                || !"S".equals(snapshot.get("subsanacion"))
                || !sameAmount(snapshot.get("impuestoTotal"), original.getTotalTax())
                || !sameAmount(snapshot.get("total"), original.getTotalAmount())) {
            throw new IllegalArgumentException(
                    "La subsanacion no puede modificar importes ni impuestos");
        }
    }

    private static boolean sameAmount(Object value, BigDecimal expected) {
        return value instanceof BigDecimal amount && amount.compareTo(expected) == 0;
    }

    private record FiscalContext(
            String issuerTaxId,
            String timezone,
            OffsetDateTime generatedAt,
            CommercialDocument document,
            Customer customer) {
    }
}
