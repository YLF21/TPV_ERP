create table saas_support_ticket_comment (
    id uuid primary key,
    ticket_id uuid not null references saas_support_ticket(id),
    author varchar(80) not null,
    message text not null,
    created_at timestamp with time zone not null
);

create table saas_admin_notification_read (
    username varchar(80) not null,
    notification_id varchar(220) not null,
    read_at timestamp with time zone not null,
    primary key(username, notification_id)
);
