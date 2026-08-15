package com.tpverp.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateResolver;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreDocumentPrintConfigurationService;
import com.tpverp.backend.terminal.TerminalRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class VoucherPresentationSnapshotFactory {

    private final CurrentOrganization organization;
    private final DocumentTemplateResolver templates;
    private final StoreDocumentPrintConfigurationService printConfiguration;
    private final CommercialDocumentRepository documents;
    private final TerminalRepository terminals;
    private final ObjectMapper mapper;

    public VoucherPresentationSnapshotFactory(
            CurrentOrganization organization,
            DocumentTemplateResolver templates,
            StoreDocumentPrintConfigurationService printConfiguration,
            CommercialDocumentRepository documents,
            TerminalRepository terminals,
            ObjectMapper mapper) {
        this.organization = organization;
        this.templates = templates;
        this.printConfiguration = printConfiguration;
        this.documents = documents;
        this.terminals = terminals;
        this.mapper = mapper;
    }

    public String create(
            Voucher voucher, CommercialDocument sourceDocument, Voucher predecessor) {
        var store = organization.currentStore();
        if (!store.getId().equals(sourceDocument.getTiendaId())) {
            throw new IllegalArgumentException("documento no encontrado");
        }
        var presentation = printConfiguration.presentation(DocumentTemplateType.VALE);
        var template = templates.resolve(
                store, DocumentTemplateType.VALE, DocumentTemplateFormat.TICKET_80);
        var traceability = predecessor == null || predecessor.printSnapshot() == null
                ? legacyTrace(predecessor == null
                        ? voucher.originTickets() : predecessor.originTickets())
                : read(predecessor.printSnapshot()).traceability();
        var merged = new LinkedHashMap<String, VoucherPresentationSnapshot.TraceEntry>();
        traceability.forEach(entry -> merged.put(entry.documentNumber(), entry));
        var current = trace(sourceDocument);
        merged.put(current.documentNumber(), current);
        String terminalName = sourceDocument.getTerminalOrigenId() == null ? null
                : terminals.findByIdAndTiendaId(
                                sourceDocument.getTerminalOrigenId(), store.getId())
                        .map(com.tpverp.backend.terminal.Terminal::getNombre)
                        .orElse(null);
        var snapshot = new VoucherPresentationSnapshot(
                1,
                presentation.observations(),
                new InvoicePresentationSnapshot.TemplateReference(
                        template.id(), template.code(), template.version(),
                        template.schemaVersion(), template.sha256(), template.builtIn()),
                presentation.logo() == null ? null
                        : new InvoicePresentationSnapshot.LogoReference(
                                presentation.logo().id(), presentation.logo().contentType(),
                                presentation.logo().sha256()),
                terminalName,
                List.copyOf(merged.values()));
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("voucher_print_snapshot_serialization_failed", error);
        }
    }

    public VoucherPresentationSnapshot read(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("voucher_print_snapshot_required");
        }
        try {
            return mapper.readValue(value, VoucherPresentationSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("voucher_print_snapshot_invalid", error);
        }
    }

    public String logoDataUri(VoucherPresentationSnapshot snapshot, java.util.UUID storeId) {
        if (snapshot.logo() == null) return null;
        return printConfiguration.logoDataUri(storeId,
                new StoreDocumentPrintConfigurationService.LogoReference(
                        snapshot.logo().id(), snapshot.logo().contentType(),
                        snapshot.logo().sha256()));
    }

    private List<VoucherPresentationSnapshot.TraceEntry> legacyTrace(List<String> numbers) {
        var result = new ArrayList<VoucherPresentationSnapshot.TraceEntry>();
        for (var number : numbers) {
            var document = documents.findAllByTiendaIdAndNumeroIgnoreCase(
                    organization.currentStore().getId(), number).stream().findFirst();
            result.add(document.map(this::trace).orElseGet(() ->
                    new VoucherPresentationSnapshot.TraceEntry(
                            number, null, null, "ORIGEN")));
        }
        return result;
    }

    private VoucherPresentationSnapshot.TraceEntry trace(CommercialDocument document) {
        return new VoucherPresentationSnapshot.TraceEntry(
                document.getNumero(), document.getTipo(), document.getFecha(),
                document.getTotal().signum() < 0 ? "EMISION" : "CONSUMO_PARCIAL");
    }
}
