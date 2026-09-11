# Plan: adaptación puntual de documentos de desarrollo

## Alcance

- Incluido: simulación, preparación idempotente y verificación central de documentos de venta históricos conservados en la instalación local. Herramienta técnica manual para ADMIN, únicamente en perfil `dev` y habilitada expresamente.
- Excluido: ejecución automática al arrancar o consultar, nuevas ventas/cobros/stock, reconstrucción por orden de recepción, migrador permanente, reasignación de identidades, cambios en Flyway, borrado de datos y ejecución sobre las bases de desarrollo del usuario durante la implementación.

## Acciones

- [x] Añadir simulación paginada de tienda/período/corte fijo, con lotes de hasta 100 documentos y diagnóstico del snapshot.
- [x] Reutilizar el publicador con una primera revisión atómica solo si el documento aún no se ha publicado como v2; confirmar revisión y outbox juntos.
- [x] Añadir preparación manual con ámbito revalidado y comprobación previa del receptor; no activar ni vaciar toda la cola como efecto secundario.
- [x] Verificar en SaaS instalación, documento, evento, revisión registrada y vínculo de cliente; no equiparar ENVIADO con PROJECTED.
- [x] Proporcionar un comando PowerShell con manifiesto de lote para simular, preparar y verificar, sin mostrar ni guardar credenciales.
- [x] Probar permisos, límites, rollback, repetición y concurrencia en PostgreSQL aislado; revisar los resultados y documentar las limitaciones.

## Decisiones

- Se adapta el estado actual de los documentos originales sin recalcular importes ni inventar actores o vínculos.
- Solo se preparan documentos que no tienen fila en `documento_sync_revision`. Esa marca es duradera y se confirma junto al evento; no hace falta una tabla de trabajos. El cursor acelera la selección, pero puede reiniciarse sin duplicar publicaciones.
- Las actualizaciones normales prevalecen sobre una petición de adaptación en la misma transacción. Si una venta o cobro publica simultáneamente, el claim atómico evita otra primera revisión.
- Una revisión local ya existente no demuestra recepción central. Los documentos v2 no recibidos, ignorados o con conflicto se diagnostican; esta herramienta no borra marcas, reasigna instalaciones ni fuerza revisiones para ocultar un problema.
- El receptor debe estar actualizado antes de preparar el lote. Un documento central de la misma identidad pero sin revisión local coherente bloquea la adaptación; no se reinicia su secuencia.
- La simulación no escribe. Preparar escribe únicamente revisión, outbox y auditoría técnica. Verificar solo consulta. La comprobación previa funciona con URL y credenciales SaaS aunque el worker esté desactivado. Si ya está activo, el worker habitual puede enviar los eventos preparados conforme a su configuración y cuotas; la herramienta no modifica esa configuración.
- La consulta técnica de estado central se limita a la empresa/tienda/instalación autenticada; no otorga acceso al historial de otras instalaciones.
- Las consultas e informes de APP VENTA y APP GESTIÓN siguen siendo bajo demanda. Esta herramienta no añade cargas de arranque ni pantallas.

## Pendiente antes de ejecutar con datos del usuario

Confirmar instalación y período concretos, conservar una copia de seguridad y empezar por una simulación y un lote pequeño. La implementación y sus pruebas no autorizan esa ejecución real, reinicios, despliegue, commit ni push.

## Archivos y procedimiento

- Backend local: `DocumentSyncRecoveryApi`, `Controller`, `Service`, `Repository`, `Client` y `Exception`, en el paquete existente `document`.
- Publicación reutilizada: `DocumentSyncPublisher.scheduleIfUnpublished` y `DocumentSyncRevisionRepository.tryClaimInitialRevision`; las publicaciones operativas normales mantienen su incremento de revisión.
- SaaS: `CommercialDocumentRecoveryApi`, `Controller` y `Service`; consulta técnica acotada, sin reenvío ni modificaciones.
- Herramienta y guía: [document-sync-recovery.ps1](../tools/document-sync-recovery.ps1) y [document-sync-recovery.md](../tools/document-sync-recovery.md).
- Pruebas: `DocumentSyncRecovery*Test`, `DocumentSyncPublisher*Test` y `CommercialDocumentRecovery*Test`.

## Evidencia de esta implementación

- SaaS: `mvnw.cmd --batch-mode --no-transfer-progress verify`, 598 pruebas, cero fallos/errores/omisiones, compilación y cobertura aprobadas. PostgreSQL 17.6 aislado; incluye 38 casos del nuevo contrato, autenticación y consulta real de estado.
- Repetición focal local después de corregir las fixtures: 51 pruebas aprobadas de publicador PostgreSQL, recuperación PostgreSQL y cliente HTTP. Incluye rollback del segundo evento, carreras en ambos órdenes, reintentos, documentos/cobros/existencias intactos, límites y verificación de recibos. La revisión remota cero se acepta para diagnosticar un conflicto sin alterar el contador local desde uno.
- Backend local: suite completa `mvnw.cmd --batch-mode --no-transfer-progress verify`, 3.887 casos, cero fallos/errores y 7 omisiones por enlaces simbólicos o permisos POSIX no disponibles en este entorno Windows; compilación aprobada. Después del ajuste para consultar SaaS sin activar el scheduler y las comprobaciones de serialización, se repitieron exclusivamente cliente/controlador: 35 pruebas aprobadas y artefacto recompilado. No se suma esa repetición como si fueran 35 casos nuevos.
- PowerShell: parser real de Windows PowerShell 5.1; flujo, precisión exacta, reintentos expresos y 14 rechazos seguros en PowerShell 7.6 con transporte simulado. El HTTP real de ese script no está verificado: el entorno de herramientas impidió iniciar el fixture. No se modificaron políticas para sortearlo. El cliente Java sí se probó con un servidor HTTP local de fixtures, lo que no sustituye esta limitación del script.
- Revisión independiente de transacciones, permisos, ámbito y recibos: cerrada la incompatibilidad con revisión remota cero; no quedaron otros defectos demostrables en el alcance inspeccionado. El cursor requiere un corte explícito también en backend, no solo en el script.
- No hay cambios de interfaz, dependencias nuevas ni migraciones adicionales en esta adaptación. No se repiten las compilaciones de frontend que ya se validaron en la fase anterior.
- Flyway conserva los máximos V244 local y V57 SaaS, sin versiones de ficheros duplicadas. Las migraciones se verificaron únicamente en PostgreSQL aislado.
- `git diff --check` y comprobación equivalente de archivos nuevos sin errores de espacios. `backend-saas/docker-compose.dev.yml` conserva el hash SHA-256 `198FF6B6FC4CDA56C6AA6E4F4EF449F6E28F05BCFCEDD64B33F4CC78B5E62F0D`.
- Retirados el contenedor y volumen PostgreSQL creados para estas pruebas (solo datos sintéticos). No se modificaron BD de las aplicaciones ni se realizaron reinicios, despliegues, commit o push.

La implementación y las pruebas aisladas no equivalen a una carga histórica ya ejecutada ni a un ensayo conectado del script PowerShell. Esas comprobaciones operativas permanecen pendientes.

## Incidencia de arranque local y corrección (2026-09-10)

- El registro aportado por el usuario y una consulta de solo lectura confirman Flyway V244, con V243 y V244 aplicadas correctamente. El backend se cierra después por `FiscalRuntimeGuardInitializer`, no por Flyway ni por los avisos de APIs obsoletas/JNA.
- El marcador real conserva `SANDBOX / DUAL / tpv-erp-dev-v242 / release.sequence=10`; el manifiesto DEV nuevo había cambiado a `tpv-erp-dev-v244` sin incrementar su secuencia. La protección rechaza correctamente esa transición.
- Corregido únicamente el contador DEV en `backend/pom.xml` a `11`; no se modifica la protección, el perfil de producción ni el historial fiscal de la BD. El arranque normal registrará la nueva versión y su auditoría transaccional.
- El contrato de build detectó el valor incorrecto antes de corregirlo. Se añadió la regresión V242/10 → V244/11. Después pasan 47 pruebas de manifiesto, propiedades, contrato de build y protección de arranque, sin fallos ni omisiones; `package` y `git diff --check` correctos. El JAR y el manifiesto de `target/classes` están regenerados.
- El usuario arrancó el backend con `dev,fiscal-dev,saas-dev`. Se verificaron salud/readiness `UP`, Flyway V244 y marcador `tpv-erp-dev-v244 / release.sequence=11`. La simulación de tienda 001 y agosto de 2026 se realizó después, según la evidencia siguiente; no se han preparado documentos históricos.

La secuencia incremental es una regla interna del proyecto; no es un contador asignado por la AEAT. Esta corrección de desarrollo no certifica cumplimiento ni prepara por sí sola un release de producción. Como referencia normativa, la [AEAT exige la declaración responsable correspondiente a cada versión del sistema](https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/preguntas-frecuentes/certificacion-sistemas-informaticos-declaracion-responsable.html?faqId=4e5b77fe52572910VgnVCM100000dc381e0aRCRD).

## Simulación real autorizada y retirada del acceso temporal (2026-09-10)

- Ejecutada desde la sesión ADMIN abierta por el usuario en APP VENTA, mediante un acceso DEV temporal en Diagnóstico. Solo se llamó a `/api/v1/sync/document-recovery/preview`; no a `prepare`, `verify`, sincronización manual ni pruebas de impresora/cajón. El token permaneció en el cliente habitual, sin extraerlo ni almacenarlo en un script.
- Ámbito: tienda 001, del 01/08/2026 al 31/08/2026. Corte devuelto y conservado en las tres páginas: `2026-09-10T22:16:16.058356Z`. Páginas de 100, 100 y 6 documentos, sin duplicados ni errores de snapshot.
- Resultado: **206 documentos**, con 165 tickets (148 confirmados, 13 anulados, 3 pagados y 1 pendiente), 40 facturas de venta (37 pagadas y 3 pendientes), 1 rectificativa confirmada y ningún albarán. Recuentos contrastados con SELECT de solo lectura sobre el mismo período y tienda.
- Antes y después: los 221 documentos locales completos conservan la huella agregada MD5 `2fef7433f3a1d55957621bfc9c675f82`; los 1.538 eventos de `sync_outbox` conservan `3805a5ebe610751149e23d45d14631fe`. `documento_sync_revision` sigue en 0 y `saas_commercial_document` en 0. Huellas calculadas sobre filas serializadas en orden de ID y zona UTC; se usan para detectar cambios, no como garantía criptográfica.
- Se eliminaron el botón, su componente, sus pruebas temporales y las claves ES/EN/ZH. `HardwareSettingsScreen.tsx` y los tres catálogos coinciden byte a byte con sus SHA-256 anteriores a la prueba. No queda una pantalla ni carga de arranque adicional. La recarga de Vite devolvió la aplicación al inicio de sesión; no se reiniciaron los servicios.
- Validación: 3 pruebas del componente temporal antes de retirarlo (paginación/corte fijo y doble pulsación, error y ADMIN); 13 pruebas existentes de `HardwareSettingsScreen` aprobadas después de retirarlo. El cambio ajeno de `backend-saas/docker-compose.dev.yml` conserva su SHA-256 documentado.
- Diagnóstico también muestra incidencias operativas previas (19 eventos bloqueados y 3 recuperaciones de saldo). No se reintentaron ni modificaron: no son errores devueltos por esta simulación.

Esta prueba valida el preview HTTP real y la lectura del snapshot de esos 206 documentos. **No demuestra todavía su recepción/proyección en SaaS ni el transporte HTTP real del comando PowerShell.** La preparación, envío y verificación del histórico requieren una ejecución posterior autorizada; la simulación no los realiza.

## Carga real autorizada y conciliada (2026-09-10)

Tras la autorización posterior del usuario, se completó la carga del mismo ámbito y corte, desde su sesión ADMIN de APP VENTA. La herramienta temporal DEV utilizó el cliente autenticado existente, sin extraer tokens. Se ejecutaron cuatro lotes manuales de **5, 100, 100 y 1**; cada lote pasó por preview, prepare y verify antes de continuar. El worker habitual realizó el envío: no se cambió su configuración ni se pulsó sincronización manual.

- Copias previas actuales: `local-v244-before.dump` (V244) y `saas-v57-before.dump` (V57), en la carpeta protegida `TPV ERP Backups/document-read-model-20260910-212615/august-recovery-20260910`. Catálogos legibles y SHA-256 comprobados antes y después. Estas copias nuevas no se restauraron; las anteriores ya se habían restaurado y migrado en aislamiento.
- Resultado HTTP real: **206/206 verificados**, sin incidencias. Las cuatro auditorías `DOCUMENT_SYNC_RECOVERY_PREPARE` son EXITO, con recuentos 5, 100, 100 y 1; no hay auditoría de fallo de esta operación.
- Contraste independiente mediante transacciones SQL de solo lectura: 206 eventos locales DOCUMENTO v2 ENVIADO, 206 documentos centrales distintos, 206 eventos PROJECTED y 206 revisiones a valor 1 en ambos extremos. No hay revisiones locales fuera del ámbito autorizado.
- Los 75 documentos con cliente tienen vínculo central válido; los otros 131 son anónimos. No se crearon ni modificaron clientes en esta carga.
- La comparación ordenada de ID, tipo, estado, número, fecha, moneda, base, impuesto, total y cliente coincide entre local y SaaS: huella MD5 `d070dd4f43646a5893e2420aba749868`. También coincide la huella de ID documental/evento/revisión `094b153a0c5b2e964834781edb0ac25d`, sin discrepancias entre proyección, revisión y evento recibido. Son controles de igualdad, no garantías criptográficas ni totales fiscales interpretados.
- Los 221 documentos locales completos siguen conservando `2fef7433f3a1d55957621bfc9c675f82`; los 1.538 eventos anteriores siguen conservando `3805a5ebe610751149e23d45d14631fe`. Solo se añadieron la preparación documental, su outbox y auditoría, y la recepción/proyección central correspondiente. No se alteraron documentos originales ni importes.
- Retirados el componente `TemporaryDocumentRecoveryRun`, sus pruebas, su integración y las siete claves temporales ES/EN/ZH. Los cuatro archivos reutilizados coinciden byte a byte con sus SHA-256 previos; no queda carga temporal al arrancar. El cambio ajeno de `backend-saas/docker-compose.dev.yml` conserva su hash anterior.
- Validación de este acceso antes de retirarlo: 6 pruebas focales aprobadas (sin llamadas al montar, lotes 5/100/100/1, doble pulsación, recibos sin verificar, errores y ámbito/permisos). Después de retirarlo: **13 pruebas existentes de HardwareSettingsScreen aprobadas**, sin diferencias residuales en ese componente y `git diff --check` correcto.

Esta ejecución cierra la recuperación de los 206 documentos de agosto de 2026 de tienda 001 y valida el transporte HTTP real de los tres endpoints a través de la aplicación. **No sustituye el ensayo HTTP pendiente del script PowerShell**, que no se utilizó. No se reintentaron las incidencias operativas anteriores, no se añadieron cargas al inicio, ni se realizaron nuevos reinicios, commit o push durante esta carga.

## Ensayo posterior del transporte PowerShell (2026-09-11)

- Añadidos `tools/document-sync-recovery.http.test.cjs` y `tools/document-sync-recovery.Http.Tests.ps1`: servidor HTTP efímero limitado a loopback y cliente con datos sintéticos, sin usar BD ni credenciales reales. El script operativo no se ha modificado.
- PowerShell 7.6.5 ejecutó el flujo por HTTP real y generó/verificó los manifiestos. Pasaron las 19 peticiones previstas: cinco correctas con reintentos expresos y catorce variantes de estado, contrato y transporte. Se mantuvieron importes y revisiones exactos, nanosegundos y UTF-8; se rechazaron errores HTTP, redirecciones, JSON inválido, respuestas excesivas y recibos incompatibles sin filtrar credenciales ni cuerpos de error.
- El primer intento independiente en Windows PowerShell 5.1 se detuvo con `UnauthorizedAccess` antes de ejecutar el archivo de prueba. Posteriormente el usuario autorizó `RemoteSigned` únicamente para el proceso de prueba. Se añadió la opción explícita `--process-remote-signed` al runner y se aisló `PSModulePath` del hijo para evitar cargar los módulos de PowerShell 7 en 5.1. No se modificó el script operativo ni el entorno padre.
- Windows PowerShell **5.1.26100.9444 pasó las mismas 19 peticiones HTTP**. Se repitió 7.6.5 con la política preexistente para validar el ajuste compartido, también correcto. La comparación antes/después mantiene los cinco ámbitos de política de 5.1 en `Undefined`, y en 7.6 únicamente `LocalMachine=RemoteSigned`; no hubo cambios permanentes ni instalación de módulos.
- Servidores de fixture cerrados y manifiestos sintéticos retirados al finalizar. Sin cambios de backend/frontend, datos, configuración de sincronización, reinicios, commit o push. El cambio ajeno de Docker Compose conserva el SHA-256 documentado.

El ensayo pendiente del transporte HTTP del script queda **cerrado en PowerShell 5.1 y 7.6.5**, contra el fixture aislado, sin volver a preparar ni enviar los documentos reales. No representa un despliegue de producción ni sustituye las pruebas de autorización/transacciones del backend. Instrucciones reproducibles y límites en [la guía de la herramienta](../tools/document-sync-recovery.md#ensayo-http-real-aislado-2026-09-11).
