# Medición SQL de Resumen y Alertas

Para el catalogo fiscal, `measure_fiscal_keyset.py` ejecuta la medicion opt-in
de un millon de registros en un esquema propio de un Docker PostgreSQL aislado,
sin usar tablas `public`. Consultar
[resultado y limites de la medicion fiscal](fiscal-keyset-1m-20260918.md).

`measure_gestion_sql.py` mide lecturas con datos sintéticos en un esquema nuevo
`perf_gestion_<UUID>`. Requiere Python 3 y `psql`, sin paquetes adicionales. La
base seleccionada debe ser **temporal y aislada**, con `public` migrado con Flyway.
El script solo permite conexión local y rechaza el puerto habitual 5432.

En PowerShell, con `PGPASSWORD` ya configurado para ese PostgreSQL temporal:

```powershell
python tools/performance/measure_gestion_sql.py --port 15434 --database gestion_review_20260917010659 --output .codex-tmp/gestion-sql-performance-20260917
```

El nombre de base y puerto del ejemplo pertenecen al entorno temporal de revisión;
se deben adaptar a otro entorno aislado. La carpeta de salida debe ser nueva.

Por defecto genera 500.000 documentos, tres líneas por documento y 500.000 alertas
repartidos entre cuatro tiendas, dos empresas y 732 días. La tienda principal
concentra el 80 % del histórico. Incluye facturas derivadas, rectificativas con y
sin stock, borradores, anulaciones, importes cero y cuatro estados de alerta. Las
tablas conservan columnas, valores por defecto, restricciones CHECK e índices del
esquema migrado. No se copian filas, claves foráneas ni disparadores: la medición
es de lecturas y no valida la integridad de escritura.

Después de `VACUUM ANALYZE`, ejecuta tres muestras con `EXPLAIN (ANALYZE, BUFFERS,
FORMAT JSON)`. Resumen usa las dos consultas extraídas del código Java actual,
con periodos de 7/30/366 días, periodo anterior equivalente y filtro de almacén.
Alertas usa SQL equivalente a la lista paginada y su conteo, agrupación por regla,
búsqueda, página profunda y tarjeta de resumen. No incluye costes HTTP/JVM ni la
consulta pequeña del catálogo de reglas.

Las consultas de Alertas sin sufijo conservan el SQL previo a la optimización.
Las terminadas en `_event_store` añaden el predicado explícito de tienda también
al evento, aplicado ahora al listado y a los cinco recientes. Esta comparación
A/B comprueba el uso del índice existente `(tienda_id, ocurrido_en)` sin modificar
la aplicación ni crear índices. El contrato se verifica aparte con pruebas JPA.

La salida contiene estructura, semilla, consultas ejecutables, planes completos,
medianas/mínimos/máximos, configuración PostgreSQL y hash del repositorio Java.
La primera ejecución no se denomina «fría»: el script no vacía las cachés del
servidor o del sistema operativo. Hardware, datos y carga concurrente influyen.

El esquema se conserva para revisar los planes; `cleanup.sql` contiene únicamente
su eliminación explícita. Se puede ejecutar ese archivo con `psql` contra la
misma base temporal tras comprobar el UUID. El script no borra esquemas existentes
ni altera la estructura de la aplicación.
