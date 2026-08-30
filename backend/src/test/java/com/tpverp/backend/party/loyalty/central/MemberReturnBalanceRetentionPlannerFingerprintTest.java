package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberReturnBalanceRetentionPlannerFingerprintTest {
    @Test
    void usesSaasCanonicalOrderAndExactMoneyText() {
        var low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        var source = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var claims = List.of(
                new MemberBalanceCentralGateway.RetentionClaim(high, low, source,
                        new BigDecimal("1.00"), new BigDecimal("0.50")),
                new MemberBalanceCentralGateway.RetentionClaim(low, high, source,
                        new BigDecimal("2.00"), new BigDecimal("1.50")));

        String first = MemberReturnBalanceRetentionPlanner.fingerprint(
                source, new BigDecimal("2.00"), claims);
        String second = MemberReturnBalanceRetentionPlanner.fingerprint(
                source, new BigDecimal("2.00"), List.of(claims.get(1), claims.get(0)));

        assertThat(first).isEqualTo(second)
                .isEqualTo("ba777af17726ca8d28f61b668304375fd4fe999cc64758407c29cb8de3d2e01c");
    }
}
