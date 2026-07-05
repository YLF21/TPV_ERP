package com.tpverp.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.document.CommercialDocumentType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DevSampleDataSeederTest {

    @Test
    void coversEveryCommercialDocumentType() {
        assertThat(EnumSet.copyOf(DevSampleDataSeeder.documentTypes()))
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(CommercialDocumentType.class));
    }
}
