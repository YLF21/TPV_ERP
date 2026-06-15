package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.DocumentoRepository;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.TiendaRepository;
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
    private final LicenciaRepository licenses;
    private final EmpresaRepository companies;
    private final TiendaRepository stores;
    private final InstalacionRepository installations;
    private final DocumentoRepository documents;
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
            LicenciaRepository licenses,
            EmpresaRepository companies,
            TiendaRepository stores,
            InstalacionRepository installations,
            DocumentoRepository documents,
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

    // Registra y encadena una operacion fiscal usando solo datos persistidos y validados.
    @Transactional(noRollbackFor = VerifactuInactiveException.class)
    public FiscalRecord register(FiscalRecordCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command es obligatorio");
        }
        var generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        var context = fiscalContext(command, generatedAt);
        policy.validate(context.document(), command.operation(), command.documentType());

        // El UPSERT y el bloqueo serializan la unicidad y el avance de la cadena.
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
        var snapshot = snapshots.create(
                context.document(), context.issuerTaxId(), command.operation(),
                command.documentType(), context.customer());
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
        chain.advance(saved, generatedAt);
        return saved;
    }

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
        if (generatedAt.isBefore(license.getValidaDesde())
                || !generatedAt.isBefore(license.getValidaHasta())) {
            throw new IllegalStateException("La licencia no esta vigente");
        }
        var companyTaxId = SpanishTaxId.validate(company.getTaxId());
        var licenseTaxId = SpanishTaxId.validate(license.getTaxId());
        if (!licenseTaxId.equals(companyTaxId)) {
            throw new IllegalStateException(
                    "El NIF de la licencia no coincide con la empresa");
        }
        var customer = customer(document, command.companyId());
        var configuration = configurations.findByCompanyId(command.companyId())
                .orElseGet(() -> configurations.save(
                        new VerifactuConfiguration(command.companyId())));
        var zone = ZoneId.of(store.getTimezone());
        if (!activation.isActive(
                configuration, license.getTaxpayerType(), generatedAt, zone)) {
            throw new VerifactuInactiveException();
        }
        return new FiscalContext(
                companyTaxId, zone.getId(),
                generatedAt.atZone(zone).toOffsetDateTime(), document, customer);
    }

    private Customer customer(Documento document, UUID companyId) {
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

    private record FiscalContext(
            String issuerTaxId,
            String timezone,
            OffsetDateTime generatedAt,
            Documento document,
            Customer customer) {
    }
}
