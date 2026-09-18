# Medición SQL de Resumen y Alertas — 17 de septiembre de 2026

Medición local reproducible con [el script de rendimiento](../tools/performance/measure_gestion_sql.py).
Se usó exclusivamente el PostgreSQL temporal de revisión (127.0.0.1:15434), base
`gestion_review_20260917010659`, esquema nuevo
`perf_gestion_9ff4a8ead93b43018adbfb615e5eba31`. Ninguna fila de la aplicación se
copió o modificó. No se añadieron índices.

## Entorno y volumen

- PostgreSQL 18.3 en Windows, `shared_buffers=128 MB`, `work_mem=4 MB`, hasta dos
  trabajadores paralelos por consulta y JIT habilitado.
- Estructura/índices/CHECK copiados del esquema migrado a V246. V247 afecta a la
  auditoría de líneas eliminadas, ajena a estas consultas.
- 500.000 documentos, 1.500.000 líneas, 500.000 eventos y 500.000 alertas; cuatro
  tiendas, dos empresas, dos almacenes por tienda y 732 días. Una tienda concentra
  el 80 % del histórico. Hay 16.000 productos y 25.000 rectificativas con metadatos.
- Tres ejecuciones por consulta, después de `VACUUM ANALYZE`; carga y estadísticas
  iniciales: 101 segundos. Los datos y los índices ocupan aproximadamente 1,59 GB.
- Las consultas de Resumen se extrajeron del Java, SHA-256
  `c63031f1b921e48409a5175bacbf422bbdb5d6ffa5dccaf62b9ea644b8646167`.
  Las de Alertas son SQL equivalente a los filtros/ordenación JPA.

## Resultados de Resumen

Medianas en milisegundos. La serie diaria incluye el periodo anterior de igual
duración; el ranking usa solo el periodo seleccionado. Siempre son dos consultas.

| Periodo seleccionado | Serie diaria | Top 10 productos | Suma de medianas | Suma con almacén |
|---|---:|---:|---:|---:|
| 7 días | 77,5 | 86,3 | 163,8 | 140,8 |
| 30 días | 70,8 | 312,6 | 383,4 | 353,8 |
| 366 días | 270,9 | 1.251,9 | 1.522,8 | 624,5 |

El coste principal es el ranking anual: el plan lee 1,5 millones de líneas para
unirlas con 170.072 documentos válidos del periodo/tienda; agrega 510.216 líneas y
devuelve diez productos. El hash utiliza disco temporal (16.313 bloques leídos y
escritos, unos 127 MiB por dirección). No carga todo el histórico en el frontend.
El ranking de 30 días también elige leer todas las líneas (sin derrame temporal).

## Resultados de Alertas

| Periodo | Primera página, 25 filas | Conteo | Agrupación por regla/estado |
|---|---:|---:|---:|
| 7 días | 34,1 | 48,1 | 48,7 |
| 30 días | 40,4 | 51,5 | 61,0 |
| 366 días | 384,3 | 207,5 | 196,7 |

La búsqueda de 30 días con estado, prioridad y texto tarda unos 17 ms por
consulta. El resumen de estados de todo el histórico tarda 49,2 ms y las cinco
alertas recientes 400,5 ms. Una página anual con `OFFSET 100000` tarda 558,6 ms;
el plan ordena en disco, por lo que las páginas profundas siguen teniendo coste.

## Mejora medida sin índices nuevos

Primero se añadió en SQL experimental `event.tienda_id = tienda_actual`, además
del filtro ya existente en la alerta. Con datos coherentes por tienda, el índice
existente `(tienda_id, ocurrido_en)` puede producir directamente las primeras
filas en orden cronológico.

| Consulta | Actual | Con predicado de tienda también en evento |
|---|---:|---:|
| Primera página, 7 días | 34,1 ms | 0,25 ms |
| Primera página, 366 días | 384,3 ms | 0,25 ms |
| Cinco recientes, todo el histórico | 400,5 ms | 0,16 ms |
| Conteo, 7 días | 48,1 ms | 35,9 ms |
| Agrupación, 7 días | 48,7 ms | 37,2 ms |
| Página anual, `OFFSET 100000` | 558,6 ms | 740,2 ms |

La mejora se ha aplicado al filtro JPA del listado y a la consulta de cinco
alertas recientes. No cambia índices, DTO ni reglas de filtros/ordenación. La
comparación SQL confirmó los mismos IDs en el mismo orden para las primeras
páginas de 7/30/366 días, la página profunda y los cinco recientes. La diferencia
simétrica de los conjuntos completos fue cero (3.825, 16.393 y 200.000 alertas,
respectivamente). Se añadió una regresión PostgreSQL de recientes, límite de
cinco, aislamiento de tienda, empates cronológicos, eventos detectados tarde y
ausencia de consultas por fila. El lote coordinado terminó con las siete pruebas
de `ControlAlertReadPostgreSqlTest` y las tres de `ControlAlertServiceTest`
aprobadas, sin fallos ni omisiones. Los tiempos anteriores son SQL y no latencia
HTTP del endpoint completo.

No se modificaron los agregados por regla/estado ni el ranking anual. La mejora
no resuelve el coste de páginas profundas y no implica cambiar el contrato a
paginación por cursor.

## Evidencia y límites

Los SQL, planes completos JSON, parámetros y muestras están en
`.codex-tmp/gestion-sql-performance-20260917/`; el esquema se conserva para su
inspección y `cleanup.sql` contiene exclusivamente su eliminación explícita.
`summary.json` corresponde a las consultas originales y
`event-store-experiment.json` a la comparación posterior. El script actual permite
reproducir ambos lotes en un nuevo esquema.

Son tiempos del motor SQL, no latencia HTTP ni del renderizado. La primera muestra
no es una caché fría controlada. Distribución sintética, tamaño de las filas,
hardware y carga concurrente influyen; no constituyen un SLA de producción. La
copia conserva índices y CHECK pero omite FK y disparadores, por lo que no
sustituye las pruebas reales de Flyway/JPA ni verifica operaciones de escritura.
