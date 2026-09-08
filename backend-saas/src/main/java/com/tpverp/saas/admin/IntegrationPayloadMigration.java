package com.tpverp.saas.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IntegrationPayloadMigration implements ApplicationRunner {

    private static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IntegrationSecretCipher cipher;

    public IntegrationPayloadMigration(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            IntegrationSecretCipher cipher) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        while (true) {
            List<LegacyPayload> batch = jdbc.query("""
                    select id, payload from saas_integration_run
                     where payload is not null and payload <> '' and payload <> ?
                       and payload not like 'v1:%'
                     order by started_at, id
                     limit ?
                    """, (rs, row) -> new LegacyPayload(
                    rs.getObject("id", UUID.class), rs.getString("payload")),
                    SecurityPayloadRetentionService.PURGED_PAYLOAD, BATCH_SIZE);
            if (batch.isEmpty()) {
                return;
            }
            if (!cipher.configured()) {
                throw new IllegalStateException(
                        "Hay payloads de integracion sin cifrar; configura TPV_SAAS_SECRET_ENCRYPTION_KEY");
            }
            transactions.executeWithoutResult(status -> batch.forEach(payload ->
                    jdbc.update("""
                            update saas_integration_run set payload = ?
                             where id = ? and payload = ?
                            """, cipher.encrypt(payload.value()), payload.id(), payload.value())));
        }
    }

    private record LegacyPayload(UUID id, String value) {
    }
}
