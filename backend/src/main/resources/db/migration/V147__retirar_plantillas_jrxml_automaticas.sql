-- Los JRXML se gestionan exclusivamente mediante la carga manual versionada.
-- Se conservan filas y artefactos para reproducir documentos históricos.
update plantilla_documento
set estado = 'RETIRED',
    retirada_en = coalesce(retirada_en, current_timestamp),
    version = version + 1
where ambito = 'SYSTEM'
  and estado = 'ACTIVE'
  and codigo in (
      'FACTURA_A4',
      'ALBARAN_A4',
      'FACTURA_TICKET_80'
  );
