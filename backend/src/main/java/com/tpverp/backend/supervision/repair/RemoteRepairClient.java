package com.tpverp.backend.supervision.repair;

import java.util.List;
import java.util.UUID;

public interface RemoteRepairClient {
    List<RemoteRepairCommand> claim(UUID installationId);
    void report(RemoteRepairResult result);
}
