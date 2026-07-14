package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
class SalePaymentV60MigrationContractTest {
 @Test void persistsScopedVersionedSessionsAndUniqueStableAllocations() throws Exception {var sql=Files.readString(Path.of("src/main/resources/db/migration/V60__sale_payment_sessions.sql")).toLowerCase();assertThat(sql).contains("sale_payment_session","store_id","terminal_id","user_id","document_snapshot jsonb","request_hash","currency","ticket_id","version","sale_payment_allocation","idempotency_key","unique(session_id,idempotency_key)","operation_id","foreign key (terminal_id, store_id)","check(amount>0)","unique index ux_sale_payment_session_active","where status in ('collecting','covered','compensation_required')");}
}
