package com.tpverp.backend.party;

/** Stable cause for operations blocked by a missing or stale official member snapshot. */
public final class MemberBalanceOfficialSyncRequiredException extends IllegalStateException {

    public static final String CODE = "MEMBER_BALANCE_OFFICIAL_SYNC_REQUIRED";

    public MemberBalanceOfficialSyncRequiredException() {
        super("message.member.official_sync_required");
    }
}
