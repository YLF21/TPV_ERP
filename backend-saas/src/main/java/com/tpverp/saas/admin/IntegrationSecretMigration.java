package com.tpverp.saas.admin;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IntegrationSecretMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IntegrationSecretCipher cipher;

    public IntegrationSecretMigration(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            IntegrationSecretCipher cipher) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        var plaintext = jdbc.query("""
                select id, api_key
                from saas_integration_endpoint
                where api_key is not null
                  and trim(api_key) <> ''
                  and api_key_encrypted is null
                """, (rs, row) -> new LegacySecret(
                rs.getObject("id", UUID.class),
                rs.getString("api_key")));
        if (plaintext.isEmpty()) {
            return;
        }
        if (!cipher.configured()) {
            throw new IllegalStateException(
                    "Hay secretos de integracion sin cifrar; configura TPV_SAAS_SECRET_ENCRYPTION_KEY");
        }
        transactions.executeWithoutResult(status -> plaintext.forEach(secret ->
                jdbc.update("""
                        update saas_integration_endpoint
                        set api_key_encrypted = ?, api_key = null
                        where id = ? and api_key_encrypted is null
                        """, cipher.encrypt(secret.value()), secret.id())));
    }

    private record LegacySecret(UUID id, String value) {
    }
}
