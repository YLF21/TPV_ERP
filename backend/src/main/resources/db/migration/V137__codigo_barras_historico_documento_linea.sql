alter table documento_linea
    add column codigo_barras varchar(128);

comment on column documento_linea.codigo_barras is
    'Codigo de barras primario congelado al crear la linea; null en lineas especiales y documentos historicos sin evidencia.';
