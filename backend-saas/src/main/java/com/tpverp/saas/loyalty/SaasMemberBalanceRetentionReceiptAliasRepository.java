package com.tpverp.saas.loyalty;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SaasMemberBalanceRetentionReceiptAliasRepository
        extends JpaRepository<SaasMemberBalanceRetentionReceiptAlias, UUID> {
}
