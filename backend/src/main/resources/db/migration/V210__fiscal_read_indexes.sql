-- Read indexes for the fiscal control views used by APP GESTIÓN.
-- This migration is deliberately schema-only: it does not rewrite fiscal data.

create index ix_registro_fiscal_tenant_tienda
    on registro_fiscal(
        empresa_id,
        tienda_id,
        instalacion_id,
        generado_en desc,
        secuencia desc,
        id desc
    );

create index ix_exportacion_fiscal_tenant_fecha
    on exportacion_fiscal(
        empresa_id,
        instalacion_id,
        exportada_en desc,
        id desc
    );

create index ix_requerimiento_fiscal_tenant_fecha
    on requerimiento_fiscal(
        empresa_id,
        instalacion_id,
        solicitado_en desc,
        id desc
    );
