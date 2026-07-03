create table member_smtp_settings (
    empresa_id uuid primary key references empresa(id),
    enabled boolean not null default false,
    host varchar(255) not null,
    port integer not null,
    username varchar(255),
    password varchar(1024),
    from_email varchar(320) not null,
    from_name varchar(128),
    start_tls boolean not null default true,
    ssl_enabled boolean not null default false,
    version bigint not null default 0,
    constraint ck_member_smtp_port check (port between 1 and 65535)
);
