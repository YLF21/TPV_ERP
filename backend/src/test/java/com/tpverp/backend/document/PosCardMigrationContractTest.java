package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
class PosCardMigrationContractTest {
 @Test void migrationHasAtomicReservationRecoveryAndConsistencyConstraints() throws Exception {
  var sql=Files.readString(Path.of("src/main/resources/db/migration/V45__pos_card_checkout.sql"));
  var repository=Files.readString(Path.of("src/main/java/com/tpverp/backend/document/PosCardCheckoutRepository.java"));
  assertThat(sql).contains("document_snapshot JSONB NOT NULL","authorization_code VARCHAR(64)","gateway_owner","gateway_lease_until","ticket_owner","actualizado_en","schema_version","UNIQUE REFERENCES documento(id)","ck_pos_card_checkout_completion","ck_pos_card_checkout_snapshot");
  assertThat(sql).doesNotContain("authorization VARCHAR(64)");
  assertThat(sql).contains("document_snapshot ? 'ticket'").doesNotContain("document_snapshot ? 'command'");
  assertThat(repository).contains("ON CONFLICT (id) DO NOTHING");
 }
}
