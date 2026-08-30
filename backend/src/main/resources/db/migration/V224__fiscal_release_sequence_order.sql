-- Fiscal release/build ordering is numeric and append-only. It is kept
-- separate from release_id because identifiers are opaque and may contain
-- punctuation; legacy rows start at zero and can be adopted once.
alter table fiscal_runtime_guard
    add column release_sequence bigint not null default 0,
    add column build_sequence bigint not null default 0;

alter table fiscal_runtime_guard
    add constraint ck_fiscal_runtime_guard_release_sequence
        check (release_sequence >= 0),
    add constraint ck_fiscal_runtime_guard_build_sequence
        check (build_sequence >= 0);

alter table fiscal_runtime_release_audit
    add column release_sequence bigint not null default 0,
    add column build_sequence bigint not null default 0;

alter table fiscal_runtime_release_audit
    add constraint ck_fiscal_runtime_audit_release_sequence
        check (release_sequence >= 0),
    add constraint ck_fiscal_runtime_audit_build_sequence
        check (build_sequence >= 0);
