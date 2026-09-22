alter table saas_pairing_code
    add column revoked_at timestamptz,
    add column revocation_reason varchar(32),
    add constraint ck_saas_pairing_revocation
        check ((revoked_at is null) = (revocation_reason is null));

-- Retain every historical row and its original expiry/consumption. Prefer the
-- newest usable candidate (same conditions as activation-code recovery), then
-- still-unexpired candidates, then expired candidates, per store.
with ranked as (
    select p.id, row_number() over (
        partition by p.store_id
        order by (p.expires_at > current_timestamp and s.active
                  and l.status = 'VALIDA' and l.valid_until > current_timestamp) desc,
                 (p.expires_at > current_timestamp) desc, p.created_at desc, p.id desc
    ) position
    from saas_pairing_code p
    join saas_store s on s.id = p.store_id
    join saas_license l on l.id = p.license_id
    where p.consumed_at is null
)
update saas_pairing_code p
set revoked_at = current_timestamp, revocation_reason = 'LEGACY_REPLACED'
from ranked r
where p.id = r.id and r.position > 1;

-- An expired unconsumed code retains its slot until the next issuance explicitly
-- supersedes it. This stable predicate enforces uniqueness without a time-based index.
create unique index uk_saas_pairing_open_store
    on saas_pairing_code(store_id)
    where consumed_at is null and revoked_at is null;

comment on column saas_pairing_code.revoked_at is
    'Revocation preserves the code, original expiry and installation history.';
