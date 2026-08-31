ALTER TABLE documento_ajuste
    RENAME COLUMN socio_id TO member_id;

ALTER TABLE documento_ajuste
    RENAME COLUMN categoria_socio_id TO member_category_id;

ALTER TABLE documento_ajuste
    RENAME COLUMN categoria_socio_nombre TO member_category_name;
