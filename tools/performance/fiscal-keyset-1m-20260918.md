# Evidencia fiscal de volumen — 18/09/2026

## Resultado y alcance

Se midieron 1.000.000 registros sinteticos en PostgreSQL 17.6, dentro del
contenedor aislado `codex-verifactu-closure-20260918` (loopback 55449), base
`verifactu_closure_test`, esquema exclusivo
`fiscal_scale_65fcc8998b524e14b8b24904b440495d`. No se leyeron ni modificaron
tablas de la aplicacion/public, ni se utilizaron datos o credenciales reales.

800.000 registros pertenecen al ambito principal y 200.000 a otra empresa,
tienda e instalacion. Hay distintos tipos, operaciones y modalidades; los
estados de envio solo existen para VERIFACTU. El esquema minimo reproduce las
columnas de lectura y los indices reales de V210/V211, no triggers, FK ni
validacion fiscal de escritura. Incluye un marcador JSON snapshot, pero ninguna
consulta de lista lo selecciona. Tampoco se une artefacto/XML. Los planes
muestran proyeccion estrecha (275 bytes estimados), 51 resultados como maximo y
solo el join de estados necesario.

El script extrae SELECT, BASE_FROM y predicados del repositorio Java actual;
conserva su SHA-256 y los de las migraciones en `metadata.json`. Las consultas,
DDL, semilla y los 27 planes EXPLAIN ANALYZE/BUFFERS/JSON estan en:

`target/verifactu-dev-proof/cierre/fiscal-keyset-1m-20260918/`

## Mediciones

Tres muestras por consulta; no se vaciaron caches y no se afirma que una
muestra sea fria. Son tiempos del servidor SQL, no de HTTP/JVM, UI o red.

| Consulta | Mediana ms | Filas |
| --- | ---: | ---: |
| Maximo de secuencia del ambito | 0,046 | 1 |
| Primera pagina | 0,311 | 51 |
| Pagina profunda (ancla 10.000, tras 790.000 registros) | 0,305 | 51 |
| Anterior desde pagina profunda | 0,289 | 51 |
| Filtro ultimos 30 dias | 0,293 | 51 |
| Filtro 30 dias al principio del historico | 161,538 | 51 |
| Prefijo de numero situado al principio del historico | 278,196 | 51 |
| Numero exacto | 0,060 | 1 |
| Operacion + tipo + modalidad | 0,353 | 51 |

## Conclusion y decision

La navegacion keyset normal mantiene un coste similar en primera pagina y
pagina profunda; no materializa el historial en frontend ni lee XML/snapshot.
Eso **no acredita rendimiento general de todos los filtros**. Los filtros
antiguos exhiben un problema medido: el optimizador elige el indice por secuencia,
descarta 756.801 filas en el periodo antiguo y 789.001 en prefijo, y lee unos
55.000/57.000 bloques para devolver 51 resultados. La distribucion sintetica
correlaciona numero/fecha con secuencia y permite reproducir ese caso adverso.

Ya existen indices para fecha y prefijo: no se ha creado una migracion ni un
indice duplicado. El siguiente cambio debe comparar estrategias de consulta
filtrada/seleccion de candidatos y estadisticas, manteniendo orden, cursor,
aislamiento y filtros exactos. Debe acreditarse con A/B sobre esta misma semilla
y fechas no monotonicas antes de elegir una modificacion productiva. No se
recomienda desactivar globalmente indexscan ni forzar el plan del servidor.

Esta medicion no cubre concurrencia, exportaciones streaming, limites de disco,
red, escritura fiscal, carga HTTP ni todos los indices de otros modulos. No
demuestra capacidad universal para millones de registros en cualquier equipo.

## A/B posterior: candidatos materializados, sin cambiar la aplicacion

Se comparo el SQL vigente con una variante que materializa solo IDs/secuencias
filtrados, selecciona los primeros 51 y entonces une la proyeccion y el estado.
Las diez consultas se ejecutaron en transacciones READ ONLY, con los mismos
indices, sin flags del optimizador. El hash de todas las columnas devueltas,
ordenadas por secuencia/id, es identico en cada pareja (51 filas).

| Caso | SQL vigente ms | Candidatos ms |
| --- | ---: | ---: |
| Periodo antiguo de 30 dias | 166,046 | 12,958 |
| Prefijo antiguo selectivo | 283,787 | 0,885 |
| Periodo reciente de 30 dias | 0,187 | 12,601 |
| Fecha cubriendo todo el historico | 0,304 | 261,606 |
| Prefijo amplio `s-%` | 0,235 | 447,753 |

La variante beneficia filtros selectivos antiguos, pero empeora los recientes
y amplios. **Se descarta reemplazar globalmente la consulta por materializacion**.
No se ha aplicado una optimizacion productiva ni V250. Antes de ampliar este
bloque, definir un SLA y probar una estrategia selectiva sin penalizar lecturas
amplias, tambien con fechas no monotonicas y estadisticas representativas. Un
tiempo observado de 0,28 s no es por si solo un bloqueo de despliegue sin SLA;
el barrido de cientos de miles de candidatos si constituye un riesgo medido
para crecimientos posteriores. Mientras tanto, la busqueda exacta ya disponible
evita el barrido en el escenario de localizar un numero conocido.

Los 30 planes adicionales, consultas y hashes de igualdad estan en
`target/verifactu-dev-proof/cierre/fiscal-keyset-ab-20260918/`. Reproduccion:

```powershell
python -B tools/performance/compare_fiscal_filter_candidates.py `
  --container codex-verifactu-closure-20260918 --database verifactu_closure_test `
  --input target/verifactu-dev-proof/cierre/fiscal-keyset-1m-20260918 `
  --output target/verifactu-dev-proof/cierre/<carpeta-ab-nueva> --repetitions 3
```

## Reproducir

```powershell
python tools/performance/measure_fiscal_keyset.py `
  --container codex-verifactu-closure-20260918 `
  --database verifactu_closure_test --records 1000000 --repetitions 3 `
  --output target/verifactu-dev-proof/cierre/<carpeta-nueva> --confirm-isolated
```

Requiere Python estandar, Docker y psql del contenedor, no paquetes adicionales.
Solo acepta contenedor de ensayo explicito con PostgreSQL en puerto loopback no
habitual y nombre de base terminado en `_test`. Crea un esquema propio nuevo;
no borra esquemas. `cleanup.sql` queda como evidencia para una limpieza manual
posterior exclusivamente de ese UUID, tras comprobar destino y autoridad.
