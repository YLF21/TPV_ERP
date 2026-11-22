-- Preserve the reviewed origins of a shared mailbox. Legacy envelopes refer to
-- a single tenant user through source_id and keep that meaning on delivery.
alter table saas_communication_recipient add column reviewed_sources jsonb
    check (jsonb_typeof(reviewed_sources) = 'array' and jsonb_array_length(reviewed_sources) > 0);

create view saas_communication_address_source as
select s.*, case when email is null then member_id
                 else md5('espos:mailbox:' || lower(email))::uuid end as directory_id
from (
    select 'CLIENT_USER'::text kind, u.id origin_id, u.id member_id,
           coalesce(nullif(trim(r.full_name), ''), u.username) label,
           nullif(trim(r.email), '') email, u.active, u.username, c.name company_name
    from saas_tenant_user u
    left join saas_client_access_request r on r.tenant_user_id = u.id and r.status = 'APPROVED'
    left join saas_tenant_company_access a on a.user_id = u.id
    left join saas_company c on c.id = a.company_id
    union all
    select 'COMPANY_CONTACT', c.id, md5('espos:contact:' || c.id)::uuid,
           coalesce(nullif(trim(o.contact_name), ''), c.name),
           nullif(trim(o.contact_email), ''), true, null, c.name
    from saas_company_operations o join saas_company c on c.id = o.company_id
    where coalesce(nullif(trim(o.contact_name), ''), nullif(trim(o.contact_email), ''), nullif(trim(o.contact_phone), '')) is not null
    union all
    select 'COMPANY_OWNER', c.id,
           md5('espos:owner:' || c.id || ':' || coalesce(nullif(upper(trim(owner->>'taxId')), ''), (owner - 'email')::text))::uuid,
           coalesce(nullif(trim(owner->>'name'), ''), c.name),
           nullif(trim(owner->>'email'), ''), true, null, c.name
    from saas_company c cross join lateral jsonb_array_elements(c.owners) owners(owner)
    where jsonb_typeof(owner) = 'object'
) s;
