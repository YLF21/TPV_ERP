package com.tpverp.backend.verifactu;

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
    private final OfficialHashService officialHashes;
    private final FiscalJsonHasher jsonHasher;

    public FiscalRecordService(
            FiscalChainRepository chains,
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states) {
        this(chains, records, states, new OfficialHashService(), new FiscalJsonHasher());
    }

    FiscalRecordService(
            FiscalChainRepository chains,
            FiscalRecordRepository records,
            FiscalSubmissionStateRepository states,
            OfficialHashService officialHashes,
            FiscalJsonHasher jsonHasher) {
        this.chains = chains;
        this.records = records;
        this.states = states;
        this.officialHashes = officialHashes;
        this.jsonHasher = jsonHasher;
    }

    // Registra y encadena una operacion fiscal completa dentro de una unica transaccion.
    @Transactional
    public FiscalRecord register(FiscalRecordCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command es obligatorio");
        }
        var generatedAt = command.generatedAt().toInstant();

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
                command.timezone(),
                command.issuerTaxId(),
                command.totalTax(),
                command.totalAmount(),
                previousHash,
                officialHash(command, previousHash),
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

    private String officialHash(FiscalRecordCommand command, String previousHash) {
        var issueDate = DATE.format(command.issueDate());
        if (command.operation() == FiscalRecordOperation.ALTA) {
            return officialHashes.hash(new AltaHashInput(
                    command.issuerTaxId(),
                    command.number(),
                    issueDate,
                    command.documentType().name(),
                    command.totalTax(),
                    command.totalAmount(),
                    previousHash,
                    command.generatedAt()));
        }
        return officialHashes.hash(new CancellationHashInput(
                command.issuerTaxId(),
                command.number(),
                issueDate,
                previousHash,
                command.generatedAt()));
    }
}
