-- Opening evidence belongs to the session: it is visible immediately, including open sessions.
-- Historical sessions have no counted evidence and must not create retrospective alerts.
alter table sesion_caja
    add column apertura_esperado numeric(19,2),
    add column apertura_contado numeric(19,2),
    add column apertura_diferencia numeric(19,2),
    add column apertura_desglose jsonb,
    add column apertura_revisada_por uuid references usuario(id),
    add column apertura_revisada_en timestamptz,
    add column apertura_comentario_revision varchar(1000),
    add column apertura_revision_version bigint not null default 0,
    add column fondo_dejado_desglose jsonb,
    add constraint sesion_caja_apertura_evidencia_ck check (
        (apertura_esperado is null and apertura_contado is null and apertura_diferencia is null)
        or (apertura_esperado is not null and apertura_contado is not null and apertura_diferencia is not null
            and apertura_contado >= 0 and apertura_contado = fondo_inicial
            and apertura_diferencia = apertura_contado - apertura_esperado)),
    add constraint sesion_caja_apertura_revision_ck check (
        (apertura_revisada_por is null and apertura_revisada_en is null and apertura_comentario_revision is null)
        or (apertura_diferencia is not null and apertura_diferencia <> 0 and apertura_revisada_por is not null and apertura_revisada_en is not null
            and apertura_comentario_revision is not null and length(trim(apertura_comentario_revision)) > 0)),
    add constraint sesion_caja_apertura_desglose_ck check (apertura_desglose is null or jsonb_typeof(apertura_desglose) = 'array'),
    add constraint sesion_caja_fondo_desglose_ck check (fondo_dejado_desglose is null or jsonb_typeof(fondo_dejado_desglose) = 'array');

create index sesion_caja_alerta_apertura_idx on sesion_caja(tienda_id, abierta_en desc, id desc)
    where apertura_diferencia <> 0;
create index sesion_caja_alerta_apertura_pendiente_idx on sesion_caja(tienda_id, abierta_en desc, id desc)
    where apertura_diferencia <> 0 and apertura_revisada_en is null;
