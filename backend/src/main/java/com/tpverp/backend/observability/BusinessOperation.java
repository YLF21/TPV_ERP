package com.tpverp.backend.observability;

record BusinessOperation(String domain, String name, String auditEvent) {

    boolean isAudited() {
        return auditEvent != null;
    }
}
