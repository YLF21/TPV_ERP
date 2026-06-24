package com.tpverp.backend.party;

import com.tpverp.backend.organization.Tienda;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PartyCodeAllocator {

    private static final String NEXT_LOCAL = """
            insert into party_code_counter (scope_id, code_type, last_number)
            values (?, ?, 1)
            on conflict (scope_id, code_type)
            do update set last_number = party_code_counter.last_number + 1
            returning last_number
            """;

    private final JdbcTemplate jdbc;

    public PartyCodeAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextClient(Tienda store) {
        return "C-" + store.getCodigoTienda() + "-"
                + six(next(store.getId(), PartyCodeType.CLIENT));
    }

    public String nextMember(Tienda store) {
        return "M-" + store.getCodigoTienda() + "-"
                + six(next(store.getId(), PartyCodeType.MEMBER));
    }

    private long next(UUID scopeId, PartyCodeType type) {
        return Objects.requireNonNull(
                jdbc.queryForObject(NEXT_LOCAL, Long.class, scopeId, type.name()));
    }

    private String six(long number) {
        if (number < 1 || number > 999_999) {
            throw new IllegalStateException("Se ha agotado la numeracion de " + number);
        }
        return "%06d".formatted(number);
    }
}
