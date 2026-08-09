create table plantilla_documento (
    id uuid primary key,
    empresa_id uuid references empresa(id) on delete cascade,
    tienda_id uuid references tienda(id) on delete cascade,
    tipo varchar(32) not null,
    ambito varchar(16) not null,
    codigo varchar(80) not null,
    version_plantilla integer not null,
    nombre varchar(160) not null,
    estado varchar(16) not null,
    schema_version integer,
    artifact_reference varchar(512),
    sha256 varchar(64),
    creada_por_usuario_id uuid references usuario(id) on delete set null,
    creada_en timestamptz not null,
    validada_en timestamptz,
    activada_en timestamptz,
    retirada_en timestamptz,
    version bigint not null default 0,
    constraint fk_plantilla_documento_tienda_empresa
        foreign key (tienda_id, empresa_id) references tienda(id, empresa_id) on delete cascade,
    constraint ck_plantilla_documento_version check (version_plantilla > 0),
    constraint ck_plantilla_documento_schema check (schema_version is null or schema_version > 0),
    constraint ck_plantilla_documento_sha256 check (sha256 is null or sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_plantilla_documento_ambito check (
        (ambito = 'SYSTEM' and empresa_id is null and tienda_id is null)
        or (ambito = 'COMPANY' and empresa_id is not null and tienda_id is null)
        or (ambito = 'STORE' and empresa_id is not null and tienda_id is not null)
    ),
    constraint ck_plantilla_documento_estado check (
        estado in ('DRAFT', 'VALIDATED', 'ACTIVE', 'RETIRED')
    ),
    constraint ck_plantilla_documento_artifacto check (
        estado = 'DRAFT'
        or (schema_version is not null
            and artifact_reference is not null
            and sha256 is not null
            and validada_en is not null)
    ),
    constraint ck_plantilla_documento_activacion check (
        estado not in ('ACTIVE', 'RETIRED') or activada_en is not null
    ),
    constraint ck_plantilla_documento_retirada check (
        estado <> 'RETIRED' or retirada_en is not null
    )
);

create unique index uk_plantilla_documento_system_version
    on plantilla_documento (codigo, version_plantilla)
    where ambito = 'SYSTEM';

create unique index uk_plantilla_documento_company_version
    on plantilla_documento (empresa_id, codigo, version_plantilla)
    where ambito = 'COMPANY';

create unique index uk_plantilla_documento_store_version
    on plantilla_documento (tienda_id, codigo, version_plantilla)
    where ambito = 'STORE';

create unique index uk_plantilla_documento_system_active
    on plantilla_documento (tipo)
    where ambito = 'SYSTEM' and estado = 'ACTIVE';

create unique index uk_plantilla_documento_company_active
    on plantilla_documento (empresa_id, tipo)
    where ambito = 'COMPANY' and estado = 'ACTIVE';

create unique index uk_plantilla_documento_store_active
    on plantilla_documento (tienda_id, tipo)
    where ambito = 'STORE' and estado = 'ACTIVE';

create index idx_plantilla_documento_resolucion
    on plantilla_documento (tipo, estado, tienda_id, empresa_id);

insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), 'DOCUMENT_TEMPLATES_MANAGE',
       'document.templates.permissions.manage', 'DOCUMENTS'
where not exists (
    select 1 from permiso where codigo = 'DOCUMENT_TEMPLATES_MANAGE'
);
