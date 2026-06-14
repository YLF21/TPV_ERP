package com.tpverp.backend.verifactu;

import com.tpverp.backend.document.DocumentoRepository;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.EmpresaRepository;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.TiendaRepository;
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
    private final OfficialHashService officialHashes;
    private final FiscalJsonHasher jsonHasher;

    public FiscalRecordService(
            FiscalChainRepository chains,
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            EmpresaRepository companies,
            TiendaRepository stores,
            InstalacionRepository installations,
            DocumentoRepository documents) {
        this.chains = chains;
        this.records = records;
        this.states = states;
        this.companies = companies;
        this.stores = stores;
        this.installations = installations;
        this.documents = documents;
        officialHashes = new OfficialHashService();
        jsonHasher = new FiscalJsonHasher();
    }

    // Registra y encadena una operacion fiscal usando identidad persistida y validada.
    @Transactional
    public FiscalRecord register(FiscalRecordCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command es obligatorio");
        }
        var generatedAt = command.generatedAt().toInstant();
        var context = fiscalContext(command);

        // El UPSERT y el bloqueo evitan dos primeras secuencias simultaneas.
        chains.insertIfMissing(
                UUID.randomUUID(), command.companyId(), command.installationId(), generatedAt);
        var chain = chains.findForUpdate(command.companyId(), command.installationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la cadena fiscal"));
        var previousHash = chain.previousHash();
        var record = new FiscalRecord(
                chain.getId(),
                command.companyId(),
                command.installationId(),
                command.storeId(),
                command.documentId(),
                chain.nextSequence(),
                command.operation(),
                command.documentType(),
                command.number(),
                command.issueDate(),
                generatedAt,
                context.timezone(),
                context.issuerTaxId(),
                command.totalTax(),
                command.totalAmount(),
                previousHash,
                officialHash(command, context, previousHash),
                jsonHasher.hash(command.snapshot()),
                command.snapshot(),
                command.formatVersion(),
                command.algorithmVersion(),
                command.applicationVersion());

        records.save(record);
        states.save(new FiscalSubmissionState(
                record.getId(), FiscalSubmissionStatus.PENDIENTE, generatedAt));
        chain.advance(record, generatedAt);
        return record;
    }

    private FiscalContext fiscalContext(FiscalRecordCommand command) {
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
                command.generatedAt().toInstant().atZone(zone).toOffsetDateTime());
    }

    private String officialHash(
            FiscalRecordCommand command,
            FiscalContext context,
            String previousHash) {
        var issueDate = DATE.format(command.issueDate());
        if (command.operation() == FiscalRecordOperation.ALTA) {
            return officialHashes.hash(new AltaHashInput(
                    context.issuerTaxId(),
                    command.number(),
                    issueDate,
                    command.documentType().name(),
                    command.totalTax(),
                    command.totalAmount(),
                    previousHash,
                    context.generatedAt()));
        }
        return officialHashes.hash(new CancellationHashInput(
                context.issuerTaxId(),
                command.number(),
                issueDate,
                previousHash,
                context.generatedAt()));
    }

    private static IllegalArgumentException invalid(String entity) {
        return new IllegalArgumentException(
                "No existe " + entity + " para el registro fiscal");
    }

    private record FiscalContext(
            String issuerTaxId,
            String timezone,
            OffsetDateTime generatedAt) {
    }
}
