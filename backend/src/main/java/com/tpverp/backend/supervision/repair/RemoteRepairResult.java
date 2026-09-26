package com.tpverp.backend.supervision.repair;

import java.util.UUID;

public record RemoteRepairResult(UUID commandId, UUID installationId, String status, String resultCode) { }
