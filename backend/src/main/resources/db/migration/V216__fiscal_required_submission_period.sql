alter table requerimiento_fiscal
    add column periodo_inicio timestamptz,
    add column periodo_fin timestamptz,
    add constraint ck_requerimiento_fiscal_periodo
        check ((periodo_inicio is null and periodo_fin is null)
            or (periodo_inicio is not null and periodo_fin is not null
                and periodo_fin >= periodo_inicio));
