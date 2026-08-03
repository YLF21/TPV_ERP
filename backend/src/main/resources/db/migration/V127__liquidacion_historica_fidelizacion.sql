create table member_document_loyalty_settlement (
    documento_id uuid primary key references documento(id),
    miembro_id uuid not null references miembro(id),
    document_amount numeric(19,2) not null,
    eligible_document_amount numeric(19,2) not null default 0,
    eligible_paid_amount numeric(19,2) not null default 0,
    generated_points bigint not null default 0,
    granted_points bigint not null default 0,
    points_applied_to_debt bigint not null default 0,
    generated_balance numeric(19,2) not null default 0,
    granted_balance numeric(19,2) not null default 0,
    balance_applied_to_debt numeric(19,2) not null default 0,
    member_balance_used numeric(19,2) not null default 0,
    reversed_eligible_amount numeric(19,2) not null default 0,
    reversed_points bigint not null default 0,
    reversed_balance numeric(19,2) not null default 0,
    restored_member_balance numeric(19,2) not null default 0,
    return_points_debt_created bigint not null default 0,
    return_balance_debt_created numeric(19,2) not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint ck_member_document_loyalty_non_negative check (
        document_amount >= 0
        and eligible_document_amount >= 0
        and eligible_paid_amount >= 0
        and generated_points >= 0
        and granted_points >= 0
        and points_applied_to_debt >= 0
        and generated_balance >= 0
        and granted_balance >= 0
        and balance_applied_to_debt >= 0
        and member_balance_used >= 0
        and reversed_eligible_amount >= 0
        and reversed_points >= 0
        and reversed_balance >= 0
        and restored_member_balance >= 0
        and return_points_debt_created >= 0
        and return_balance_debt_created >= 0),
    constraint ck_member_document_loyalty_points_breakdown check (
        generated_points = granted_points + points_applied_to_debt),
    constraint ck_member_document_loyalty_balance_breakdown check (
        generated_balance = granted_balance + balance_applied_to_debt),
    constraint ck_member_document_loyalty_reversal_limits check (
        eligible_document_amount <= document_amount
        and eligible_paid_amount <= eligible_document_amount
        and
        reversed_eligible_amount <= eligible_document_amount
        and reversed_points <= generated_points
        and reversed_balance <= generated_balance
        and restored_member_balance <= member_balance_used)
);

create index ix_member_document_loyalty_member
    on member_document_loyalty_settlement(miembro_id, updated_at desc);

create table member_document_loyalty_line (
    documento_linea_id uuid primary key references documento_linea(id),
    documento_id uuid not null references member_document_loyalty_settlement(documento_id),
    eligible boolean not null,
    eligible_amount numeric(19,2) not null default 0,
    constraint ck_member_document_loyalty_line_amount check (
        eligible_amount >= 0 and (eligible or eligible_amount = 0))
);

create index ix_member_document_loyalty_line_document
    on member_document_loyalty_line(documento_id);
