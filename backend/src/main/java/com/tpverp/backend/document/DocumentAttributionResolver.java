package com.tpverp.backend.document;

import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentAttributionResolver {

    private final UserAccountRepository users;
    private final TerminalRepository terminals;

    public DocumentAttributionResolver(UserAccountRepository users, TerminalRepository terminals) {
        this.users = users;
        this.terminals = terminals;
    }

    @Transactional(readOnly = true)
    public Map<UUID, Attribution> resolve(Collection<CommercialDocument> documents) {
        var values = documents == null ? java.util.List.<CommercialDocument>of() : documents.stream()
                .filter(Objects::nonNull)
                .toList();
        var userIndex = users.findAllById(values.stream()
                        .map(CommercialDocument::getOperationalUserId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        var terminalIndex = terminals.findAllById(values.stream()
                        .map(CommercialDocument::getTerminalOrigenId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Terminal::getId, Function.identity()));
        return values.stream().collect(Collectors.toMap(
                CommercialDocument::getId,
                document -> {
                    var user = userIndex.get(document.getOperationalUserId());
                    var terminal = terminalIndex.get(document.getTerminalOrigenId());
                    if (user != null && user.getTienda() != null
                            && !document.getTiendaId().equals(user.getTienda().getId())) {
                        user = null;
                    }
                    if (terminal != null
                            && !document.getTiendaId().equals(terminal.getTienda().getId())) {
                        terminal = null;
                    }
                    return new Attribution(
                            document.getOperationalUserId(),
                            user == null ? "" : user.getUserName(),
                            document.getTerminalOrigenId(),
                            terminal == null ? "" : terminal.getNombre(),
                            document.getOperationalOccurredAt());
                }));
    }

    public record Attribution(
            UUID userId,
            String userName,
            UUID terminalId,
            String terminalName,
            java.time.Instant occurredAt) {

        public static Attribution empty(CommercialDocument document) {
            return new Attribution(
                    document.getOperationalUserId(), "", document.getTerminalOrigenId(), "",
                    document.getOperationalOccurredAt());
        }
    }
}
