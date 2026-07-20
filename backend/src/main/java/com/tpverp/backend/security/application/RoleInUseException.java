package com.tpverp.backend.security.application;

public class RoleInUseException extends IllegalStateException {

    private final long assignedUsers;

    public RoleInUseException(long assignedUsers) {
        super("ROLE_IN_USE");
        this.assignedUsers = assignedUsers;
    }

    public long assignedUsers() {
        return assignedUsers;
    }
}
