# VeriFactu: observabilidad operativa local

La observabilidad fiscal local es de solo lectura y está limitada a la empresa
y la instalación activa. Las consultas devuelven agregados de estado y fechas;
nunca cargan XML, snapshots, respuestas completas ni datos de otras
instalaciones.

## Métricas Micrometer

Se actualizan cada 60 segundos (configurable con
`TPV_VERIFACTU_OBSERVABILITY_INTERVAL_MS`). Los nombres y etiquetas forman
parte del contrato operativo:

| Métrica | Tipo | Etiquetas | Significado |
| --- | --- | --- | --- |
| `tpv.verifactu.backlog` | gauge | `status` = `PENDIENTE`, `ENVIANDO` o `ENVIADO` | Registros pendientes de transporte por estado |
| `tpv.verifactu.oldest.pending.age.seconds` | gauge | ninguna | Edad del pendiente más antiguo; cero si no existe |
| `tpv.verifactu.leases.expired` | gauge | ninguna | Leases `ENVIANDO` expirados en la muestra |
| `tpv.verifactu.last.aeat.success.epoch.seconds` | gauge | ninguna | Unix timestamp del último intento `ACEPTADO` o `ACEPTADO_CON_ERRORES` |
| `tpv.verifactu.observability.collection.failures` | counter | ninguna | Fallos de consulta de la proyección |
| `tpv.verifactu.observability.last.success.epoch.seconds` | gauge | ninguna | Unix timestamp de la última recogida correcta |

No se deben añadir NIF, empresa, tienda, instalación, registro o número de
factura como etiquetas: producirían cardinalidad no acotada y podrían exponer
datos fiscales en el sistema de métricas.

## Umbrales iniciales de operación

Son puntos de partida para alertas de cada instalación y deben ajustarse con
datos reales del piloto:

- `oldest.pending.age.seconds > 900` durante 10 minutos: revisar el worker,
  certificado, conectividad y cola.
- `oldest.pending.age.seconds > 3600`: incidencia prioritaria de transporte.
- `leases.expired > 0` durante dos muestras: revisar workers obsoletos o
  tiempos de respuesta.
- `collection.failures > 0` o ausencia de una recogida correcta durante 5
  minutos: revisar PostgreSQL y la aplicación.
- El backlog o un rechazo de AEAT no pone por sí solo el health en `DOWN` ni
  bloquea ventas locales. Es estado operativo que debe gestionarse desde APP
  GESTIÓN.

## Health y readiness

El scheduler recoge la proyección y la deja en una caché atómica. El indicador
`tpvFiscal` no consulta la base de datos durante un probe: lee esa caché y
devuelve `pendingCount`, `rejectedCount`, `backlogByStatus`, leases expirados
y las fechas de recogida como detalles para operadores. El backlog y los
rechazos no ponen el health en `DOWN`.

Readiness pasa a `DOWN` si todavía no existe una recogida correcta, la última
recogida falló o la caché está obsoleta. La antigüedad máxima configurable es
`TPV_VERIFACTU_OBSERVABILITY_STALE_AFTER_MS` (por defecto, 180000 ms), y debe
ser mayor que el intervalo normal del scheduler para tolerar una ejecución
puntualmente lenta. Una siguiente recogida correcta recupera el estado `UP`.

La consulta de `lastAeatSuccessAt` usa la migración V225: un índice parcial
solo sobre resultados `ACEPTADO` y `ACEPTADO_CON_ERRORES`, ordenado por
`intentado_en DESC` y con `registro_id` para el `JOIN`. Así puede resolver el
`ORDER BY ... LIMIT 1` desde el intento más reciente sin recorrer intentos de
otros estados.

El grupo readiness incluye `tpvFiscal` en el perfil actual del backend. El
endpoint continúa protegido por la autorización de administración configurada
por la aplicación.

## Limitaciones conocidas

La proyección informa la cola local y el último resultado persistido. No prueba
la disponibilidad de AEAT ni sustituye una prueba `aeat-test`; tampoco declara
que un XML sea válido ni realiza una verificación criptográfica adicional.
