package com.tpverp.backend.control;

public enum ControlAlertStatus {
    NEW,
    REVIEWED,
    CLOSED,
    DISMISSED;

    public boolean isTerminal() {
        return this == CLOSED || this == DISMISSED;
    }
}
