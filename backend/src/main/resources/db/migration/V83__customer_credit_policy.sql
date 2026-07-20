alter table cliente
    add column if not exists credit_enabled boolean not null default true,
    add column if not exists credit_limit numeric(19,2),
    add column if not exists payment_term_days integer not null default 30,
    add column if not exists credit_blocked boolean not null default false,
    add column if not exists block_on_overdue boolean not null default false;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'cliente_credit_limit_ck'
          and conrelid = 'cliente'::regclass
    ) then
        alter table cliente
            add constraint cliente_credit_limit_ck
            check (credit_limit is null or credit_limit >= 0);
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'cliente_payment_term_days_ck'
          and conrelid = 'cliente'::regclass
    ) then
        alter table cliente
            add constraint cliente_payment_term_days_ck
            check (payment_term_days between 0 and 3650);
    end if;
end
$$;

insert into permiso (id, codigo, translation_key, grupo)
values (gen_random_uuid(), 'CUSTOMER_CREDIT_OVERRIDE',
        'document.permissions.receivables.creditOverride', 'DOCUMENTS')
on conflict (codigo) do update
set translation_key = excluded.translation_key,
    grupo = excluded.grupo;

-- A document with no outstanding balance must never remain marked as partial.
update documento document
set estado = 'PAGADO'
where document.tipo in ('ALBARAN_VENTA','FACTURA_VENTA')
  and document.estado = 'PARCIAL'
  and document.total = coalesce((
      select sum(payment.importe)
      from documento_pago payment
      where payment.documento_id = document.id
  ), 0);

create index if not exists idx_customer_credit_open_debt
    on documento(cliente_id, fecha_vencimiento)
    where tipo in ('ALBARAN_VENTA','FACTURA_VENTA')
      and estado in ('PENDIENTE','PARCIAL');
