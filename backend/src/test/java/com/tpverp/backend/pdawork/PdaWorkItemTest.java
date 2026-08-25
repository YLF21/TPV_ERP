package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PdaWorkItemTest {
    @Test
    void completesAnOpenTaskOnlyOnce() {
        var item=new PdaWorkItem(UUID.randomUUID(),PdaWorkType.TASK,"Reponer lineal",null,null,null,null,null,null,null,
                "HIGH","Pasillo 2",null,null,null,UUID.randomUUID(),Instant.parse("2026-08-25T10:00:00Z"));
        item.finish(UUID.randomUUID(),Instant.parse("2026-08-25T11:00:00Z"));
        assertThat(item.getStatus()).isEqualTo(PdaWorkStatus.DONE);
        assertThatThrownBy(()->item.cancel(UUID.randomUUID(),Instant.now())).isInstanceOf(IllegalStateException.class);
    }
}