package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.Documento;
import com.tpverp.backend.document.DocumentoRepository;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.TiendaRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FiscalRecordService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private final FiscalChainRepository chains;
    private final FiscalRecordRepository records;
    private final FiscalSubmissionStateRepository states;
    private final EmpresaRepository companies;
    private final TiendaRepository stores;
    private final InstalacionRepository installations;
    private final DocumentoRepository documents;
    private final Clock clock;
    private final OfficialHashService officialHashes;
    private final FiscalJsonHasher jsonHasher;

    public FiscalRecordService(
            FiscalChainRepository chains,
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            EmpresaRepository companies,
            TiendaRepository stores,
            InstalacionRepository installations,
            DocumentoRepository documents,
            Clock clock) {
        this.chains = chains;
        this.records = records;
        this.states = states;
        this.companies = companies;
        this.stores = stores;
        this.installations = installations;
        this.documents = documents;
        this.clock = clock;
        officialHashes = new OfficialHashService();
        jsonHasher = new FiscalJsonHasher();
    }

    // Registra y encadena una operacion fiscal usando identidad persistida y validada.
    @Transactional
    public FiscalRecord register(FiscalRecordCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command es obligatorio");
        }
        var generatedAt = Instant.now(clock);
        var context = fiscalContext(command, generatedAt);

        // El UPSERT y el bloqueo evitan dos primeras secuencias simultaneas.
        chains.insertIfMissing(
                UUID.randomUUID(), command.companyId(), command.installationId(), generatedAt);
        var chain = chains.findForUpdate(command.companyId(), command.installationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la cadena fiscal"));
        if (records.existsByDocumentIdAndOperation(
                command.documentId(), command.operation())) {
            throw new IllegalStateException(
                    "La operacion fiscal ya esta registrada para el documento");
        }
        var previousHash = chain.previousHash();
        var totalTax = amount(command.operation(), context.document().getImpuestoTotal());
        var totalAmount = amount(command.operation(), context.document().getTotal());
        var record = new FiscalRecord(
                chain.getId(),
                command.companyId(),
                command.installationId(),
                command.storeId(),
                command.documentId(),
                chain.nextSequence(),
                command.operation(),
                command.documentType(),
                context.document().getNumero(),
                context.document().getFecha(),
                generatedAt,
                context.timezone(),
                context.issuerTaxId(),
                totalTax,
                totalAmount,
                previousHash,
                officialHash(command, context, totalTax, totalAmount, previousHash),
                jsonHasher.hash(command.snapshot()),
                command.snapshot(),
                command.formatVersion(),
                command.algorithmVersion(),
                command.applicationVersion());

        var savedRecord = records.save(record);
        states.save(new FiscalSubmissionState(
                savedRecord.getId(), FiscalSubmissionStatus.PENDIENTE, generatedAt));
        chain.advance(savedRecord, generatedAt);
        return savedRecord;
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
        var zone = ZoneId.of(store.getTimezone());
        return new FiscalContext(
                SpanishTaxId.validate(company.getTaxId()),
                zone.getId(),
                generatedAt.atZone(zone).toOffsetDateTime(),
                document);
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
                    context.issuerTaxId(),
                    document.getNumero(),
                    issueDate,
                    command.documentType().name(),
                    totalTax,
                    totalAmount,
                    previousHash,
                    context.generatedAt()));
        }
        return officialHashes.hash(new CancellationHashInput(
                context.issuerTaxId(),
                document.getNumero(),
                issueDate,
                previousHash,
                context.generatedAt()));
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
            Documento document) {
    }
}
