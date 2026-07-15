package com.tpverp.backend.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

@Component
public class PosCashTicketSnapshot {
    private final ObjectMapper mapper;

    public PosCashTicketSnapshot() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    PosCashTicketSnapshot(ObjectMapper mapper) { this.mapper = mapper; }

    String serialize(TicketPrintView ticket) {
        try {
            return mapper.writeValueAsString(new Snapshot(1, ticket));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cash_ticket_snapshot_serialization_failed", error);
        }
    }

    TicketPrintView deserialize(String json) {
        try {
            var snapshot = mapper.readValue(json, Snapshot.class);
            if (snapshot.schemaVersion() != 1 || snapshot.ticket() == null) {
                throw new IllegalStateException("cash_ticket_snapshot_invalid");
            }
            return snapshot.ticket();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cash_ticket_snapshot_invalid", error);
        }
    }

    record Snapshot(int schemaVersion, TicketPrintView ticket) {}
}
