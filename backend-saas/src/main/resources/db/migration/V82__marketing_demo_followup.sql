-- Internal follow-up belongs to the admin inbox, never to the public receipt.
alter table saas_marketing_demo_request
    add column assignee_user_id uuid references saas_admin_user(id),
    add column next_action varchar(500),
    add column next_action_at timestamptz,
    add column follow_up_version bigint not null default 0,
    add constraint ck_marketing_demo_follow_up_version check (follow_up_version >= 0),
    add constraint ck_marketing_demo_next_action check (
        (next_action is null and next_action_at is null)
        or (next_action is not null and length(btrim(next_action)) > 0)
    );

create index idx_marketing_demo_assignee on saas_marketing_demo_request(assignee_user_id);
create index idx_marketing_demo_next_action on saas_marketing_demo_request(next_action_at)
    where next_action_at is not null and status <> 'CLOSED';

-- Notes are immutable entries with an author snapshot and a stable order.
create table saas_marketing_demo_note (
    id uuid primary key,
    request_id uuid not null references saas_marketing_demo_request(id),
    author varchar(160) not null,
    body varchar(2000) not null check (length(btrim(body)) > 0),
    created_at timestamptz not null
);
create index idx_marketing_demo_note_request
    on saas_marketing_demo_note(request_id, created_at desc, id desc);
