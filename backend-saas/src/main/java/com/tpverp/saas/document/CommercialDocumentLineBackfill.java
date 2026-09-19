package com.tpverp.saas.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resumable bounded backfill from already received events; each document commits independently. */
@Component
public class CommercialDocumentLineBackfill {
    private final CommercialDocumentLineProjectionRepository lines;
    private final int batchSize;

    public CommercialDocumentLineBackfill(CommercialDocumentLineProjectionRepository lines,
            @Value("${tpv.saas.document-lines.backfill-batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("line backfill batch: 1..1000");
        this.lines = lines;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${tpv.saas.document-lines.backfill-initial-delay:30000}",
            fixedDelayString = "${tpv.saas.document-lines.backfill-delay:10000}")
    public int runBatch() {
        int completed = 0;
        for (var key : lines.pending(batchSize)) {
            if (lines.backfill(key)) completed++;
        }
        return completed;
    }
}
