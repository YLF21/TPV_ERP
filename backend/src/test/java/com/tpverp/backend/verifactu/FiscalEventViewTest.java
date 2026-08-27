package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalEventViewTest {

    @Test
    void mapsOnlySafeEventMetadataWithoutExposingXmlPayloads() {
        var event = new FiscalEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7,
                FiscalEventType.SUMMARY, FiscalMode.NO_VERIFACTU,
                Instant.parse("2026-08-26T10:00:00Z"), "PREVIOUS", "EVENT-HASH",
                "<unsigned>secret</unsigned>", "<signed>secret</signed>",
                "XML-HASH", Instant.parse("2026-08-26T10:00:01Z"));

        var view = FiscalEventView.from(event);

        assertThat(view.id()).isEqualTo(event.getId());
        assertThat(view.sequence()).isEqualTo(7);
        assertThat(view.hash()).isEqualTo("EVENT-HASH");
        assertThat(view.xmlHash()).isEqualTo("XML-HASH");
        assertThat(view.signed()).isTrue();
        assertThat(FiscalEventView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("unsignedXml", "signedXml");
    }
}
