package com.tpverp.backend.verifactu;

import java.util.List;

@FunctionalInterface
public interface IntegrityProgressListener {
    void onProgress(long billingChecked, long eventsChecked, long anomaliesTotal,
            long billingAnomalies, long eventAnomalies, List<String> anomalies);

    IntegrityProgressListener NONE = (billing, events, total, billingAnomalies,
            eventAnomalies, anomalies) -> { };
}
