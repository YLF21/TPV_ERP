create table communication_template (
 id uuid primary key, empresa_id uuid not null references empresa(id),
 name varchar(120) not null, subject varchar(250) not null, heading varchar(250) not null,
 body text not null, button_text varchar(80), button_url varchar(2048), logo_url varchar(2048), accent_color varchar(7) not null,
 version bigint not null default 0,
 unique(id, empresa_id)
);
create table communication_campaign (
 id uuid primary key, empresa_id uuid not null references empresa(id),
 name varchar(120) not null, template_id uuid not null,
 selected_customer_ids text not null default '',
 status varchar(20) not null default 'DRAFT' check(status in ('DRAFT','SCHEDULED','COMPLETED','CANCELLED')),
 scheduled_at timestamptz, created_at timestamptz not null, recipient_count integer not null default 0,
 version bigint not null default 0,
 foreign key(template_id, empresa_id) references communication_template(id, empresa_id), unique(id, empresa_id)
);
create table communication_welcome_template (
 empresa_id uuid primary key references empresa(id), template_id uuid not null,
 foreign key(template_id,empresa_id) references communication_template(id,empresa_id)
);
create table communication_unsubscribe_token (
 id uuid primary key, empresa_id uuid not null references empresa(id),
 customer_id uuid not null references cliente(id), email varchar(320) not null,
 created_at timestamptz not null, used_at timestamptz
);
create table communication_suppression (
 empresa_id uuid not null references empresa(id), email varchar(320) not null,
 created_at timestamptz not null, reason varchar(40) not null, primary key(empresa_id,email)
);
create table communication_delivery (
 id uuid primary key, empresa_id uuid not null references empresa(id), campaign_id uuid not null,
 customer_id uuid not null references cliente(id), email varchar(320) not null,
 subject varchar(250) not null, text_body text not null, html_body text not null, unsubscribe_url varchar(4096) not null,
 status varchar(20) not null default 'PENDING' check(status in ('PENDING','SENDING','ACCEPTED','FAILED','UNCERTAIN','SUPPRESSED','CANCELLED')),
 created_at timestamptz not null, started_at timestamptz, sent_at timestamptz, error_message varchar(500),
 attempts integer not null default 0, next_attempt_at timestamptz,
 foreign key(campaign_id, empresa_id) references communication_campaign(id, empresa_id),
 unique(campaign_id,email)
);
create index ix_communication_delivery_pending on communication_delivery(status,created_at);
create index ix_communication_campaign_company on communication_campaign(empresa_id,created_at);
create table communication_consent_audit (
 id bigserial primary key, empresa_id uuid not null, customer_id uuid not null,
 consent boolean not null, channel_id uuid, recorded_at timestamptz not null default current_timestamp,
 source varchar(40) not null
);
create function audit_communication_consent() returns trigger language plpgsql as $$
begin
 if TG_OP = 'INSERT' or NEW.commercial_consent is distinct from OLD.commercial_consent
    or NEW.preferred_commercial_channel_id is distinct from OLD.preferred_commercial_channel_id then
  insert into communication_consent_audit(empresa_id,customer_id,consent,channel_id,source)
  values(NEW.empresa_id,NEW.id,NEW.commercial_consent,NEW.preferred_commercial_channel_id,'CUSTOMER_PROFILE');
 end if;
 return NEW;
end $$;
create trigger tr_communication_consent after insert or update of commercial_consent,preferred_commercial_channel_id
 on cliente for each row execute function audit_communication_consent();
