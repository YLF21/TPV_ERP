-- Ciphertext expands UTF-8 credentials; retain capacity for existing long passwords after protection.
alter table member_smtp_settings alter column password type text;
alter table member_card_delivery add column attempt_count integer not null default 0;
alter table member_card_delivery add column html_body text;
alter table member_card_delivery add column next_attempt_at timestamptz;
alter table member_card_delivery add column claimed_at timestamptz;
alter table member_card_delivery add column resend_request_id uuid;
create unique index ux_member_card_resend_request on member_card_delivery (miembro_id, resend_request_id)
    where resend_request_id is not null;
alter table member_card_delivery drop constraint ck_member_card_delivery_status;
alter table member_card_delivery add constraint ck_member_card_delivery_status
    check (status in ('PENDIENTE', 'ENVIANDO', 'INCIERTO', 'ENVIADO', 'ERROR'));
create index ix_member_card_delivery_due on member_card_delivery (next_attempt_at, created_at)
    where status = 'PENDIENTE';
create index ix_member_card_delivery_member on member_card_delivery (miembro_id);
