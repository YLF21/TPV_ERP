# Plan: consultas comunes de documentos comerciales en SaaS

Construir un modelo de lectura relacional compartido para consultar documentos ya recibidos en SaaS por empresa emisora, tienda, cliente, usuario y fecha. La operación de venta, cobros, stock y fiscalidad continúa en cada instalación local. El modelo central conserva los importes históricos: no los recalcula.

Estado actualizado al 11/09/2026: implementación y pruebas por fases completadas; receptor y emisor activados en desarrollo, con **206/206 documentos de agosto de tienda 001 conciliados**. El ensayo HTTP del script también está cerrado en PowerShell 5.1 y 7.6.5. La evidencia operativa posterior prevalece sobre las notas históricas de «no desplegado» de cada fase: véase [recuperación y validación](document-sync-oneoff-recovery.md). No se afirma despliegue de producción, impresión física ni E2E completo de todas las pantallas contra servicios reales.

## Alcance

- Incluido: tickets, facturas de venta, rectificativas y albaranes de venta sincronizados; historial de clientes, exportación y resumen anual informativo como primeros consumidores; consultas posteriores mensuales y por usuario/tienda.
- Excluido: trasladar la operativa de caja al SaaS, documentos de compra, rehacer VeriFactu, avisos de sincronización pendiente, almacenes analíticos externos y nuevas dependencias.
- Los informes centrales mostrarán exclusivamente lo recibido en SaaS, sin mezclar silenciosamente resultados locales.
- El resumen anual mantiene las reglas ya confirmadas: informativo, sin mínimo, facturas y rectificativas con su signo, fecha comercial, cuatro trimestres y todas las tiendas de la misma empresa emisora. No es una declaración tributaria.

## Pasos

- [x] Verificar el squash de PR #153 (`c2b40a14`) y crear `codex/saas-document-read-model` desde `backend/main`, conservando los cambios privados existentes.
- [x] Implementar la base SaaS: migración aditiva, contrato documental versionado y proyección transaccional con una fila vigente por documento de origen. Verificada en PostgreSQL aislado; no desplegada.
- [x] Enriquecer el emisor local mediante su outbox existente: revisión monotónica propia del snapshot, identidad, actores, terminal de origen, fechas y relaciones completas. Verificado en PostgreSQL aislado; no desplegado.
- [x] Cubrir todos los cambios documentales: confirmaciones, cobros, anulaciones, edición administrativa permitida y relaciones añadidas después de confirmar. No publicar snapshots anteriores al estado definitivo de la transacción.
- [x] Añadir consultas SQL comunes, filtros validados, paginación por cursor y agregaciones por fecha, empresa, tienda, cliente y usuario; resolver clientes mediante el vínculo central verificado. La cuarta fase incorpora los adaptadores y la vinculación multitienda explícita.
- [x] Conectar las tres pestañas del cliente, exportación y PDF anual con el servicio común a través del backend autenticado. Mantener permisos efectivos y ES/EN/ZH. Implementado y validado en aislamiento, sin desplegar.
- [x] Implementar la adaptación puntual DEV del histórico desde snapshots originales, en lotes manuales e idempotentes. Ejecutada y conciliada para los 206 documentos autorizados; véase `document-sync-oneoff-recovery.md`.
- [x] Verificar integridad, concurrencia, aislamiento, fechas y signos en pruebas aisladas; activar receptor y emisor DEV y conciliar el histórico autorizado. Las consultas/UI están implementadas y probadas por componentes; no equivale a E2E completo contra servicios reales ni define una métrica de ventas netas.

## Decisiones técnicas

### Un documento no equivale a un evento

`saas_sync_event` conserva el registro de recepción. La nueva `saas_commercial_document` mantiene un único snapshot vigente por `(company_id, store_id, source_document_id)`. `saas_commercial_document_revision` conserva únicamente la revisión, su huella y la referencia al evento, para detectar también reenvíos contradictorios de revisiones antiguas sin duplicar el JSON. No se reutiliza `saas_sales_document`, que contiene documentos creados manualmente en SaaS y tiene otra procedencia.

La consulta existente `aggregateSales` suma eventos y no constituye la base del nuevo modelo: una actualización puede duplicar un importe y una anulación no elimina el evento original. La capa común nueva no la reutiliza. La sustitución de sus consumidores queda para la integración posterior, con la métrica explícita de cada informe, no como una modificación silenciosa de informes actuales.

### Contrato nuevo y compatibilidad

- `DOCUMENTO` sin `schemaVersion`, o con versión numérica entera `1`: se conserva el comportamiento legado, almacenado como evento pero no incorporado al nuevo modelo fiable. No enviar la versión como fracción o cadena JSON.
- `DOCUMENTO` con `schemaVersion: 2`: requiere `sourceRevision`, entero no negativo y monotónico para el snapshot completo. No se deduce del orden de llegada, de `storeSequence` ni directamente del `@Version` de JPA.
- Cabecera: `tipo`, `numero`, `estado`, `fecha`, `subtotal`, `impuestos`, `total`, `moneda`; `clienteId` es local y opcional.
- Procedencia opcional de esta primera base: `creadoPor`, `confirmadoPor`, `creadoEn`, `confirmadoEn`, `terminalOrigenId`. Un dato ausente queda desconocido, nunca se inventa.
- Los importes de cabecera llegan como cadenas decimales exactas, igual que en el emisor local actual, sin redondeo ni cálculo de nuevas bases o impuestos. Se rechazan números de coma flotante para no aceptar pérdida de precisión previa a la validación. Los precios unitarios de tres decimales del sistema local no se modifican.
- Empresa, tienda e instalación se obtienen del evento autenticado, no de identificadores duplicados dentro del JSON.

Una revisión mayor sustituye el snapshot vigente; una menor no lo sobrescribe. Una repetición con la misma revisión y contenido es idempotente; la misma revisión con distinto contenido es conflicto. El evento y su proyección se confirman o revierten juntos. Un bloqueo transaccional por documento protege incluso la primera inserción concurrente.

La misma clave documental no puede ser reclamada silenciosamente por otra instalación. La recuperación o sustitución de una instalación necesita conservar la identidad de origen o un procedimiento técnico explícito; no se resuelve aceptando un UUID con revisión superior.

### Identidades, relaciones y semántica

- El UUID local del cliente no es necesariamente su UUID SaaS. La relación ya validada en `saas_customer_identity_link`, acotada por instalación y empresa, es la referencia para resolverlo. El documento puede recibirse antes que ese vínculo.
- El usuario y el terminal conservan su identidad de origen; no se unen a usuarios centrales por coincidencia de UUID o nombre.
- Se conservarán relaciones `FACTURA_DE`, `RECTIFICA` y `COMPENSA` antes de exponer totales de ventas lógicas. El historial documental puede mostrar ticket y factura derivada; sumar ventas no puede contar ambas operaciones dos veces.
- No se deduce deuda como `total - sum(pagos)`: existen facturas liquidadas por documento origen y rectificativas con devoluciones gestionadas separadamente. La fase de deuda debe recibir el saldo reconocido y su semántica desde origen.
- No se sumarán monedas distintas en un solo importe. Los periodos usan `fecha` comercial, no `received_at`.
- La retención del evento fuente y sus dependencias debe contemplarse antes de purgar historial. Esta fase no elimina eventos ni datos.

## Primera fase de implementación

Entregable acotado: receptor funcional de snapshots v2, esquema relacional y pruebas reales de PostgreSQL/HTTP. No habilita todavía consultas para tiendas ni cambia el historial o el PDF anual. Al cerrar esta primera fase, el emisor local aún producía eventos legados sin revisión.

Archivos implementados: migración SaaS V54; paquete `com.tpverp.saas.document` (snapshot, repositorio y proyector); integración con `SyncEventService`; pruebas unitarias del contrato e integración aislada; expectativa de Flyway en `AdminApiTest`.

Validación: versiones consecutivas y fuera de orden, reintentos, misma revisión conflictiva, anulaciones, rectificativas negativas, tipos y fechas inválidos, precisión, permisos de instalación, dos tiendas/empresas, inserción concurrente, rollback y compatibilidad de eventos legados. Ejecutar `backend-saas/mvnw.cmd verify` con PostgreSQL efímero, sin tocar la BD de negocio.

### Evidencia de cierre de esta fase — 10/09/2026

- Pruebas focalizadas de `CommercialDocumentSnapshotTest`, `CommercialDocumentSyncProjectorTest` y `CommercialDocumentSyncPostgreSqlTest`: **113 casos, cero fallos/errores**, incluidos 14 casos de integración PostgreSQL/HTTP.
- Suite completa `backend-saas/mvnw.cmd --batch-mode --no-transfer-progress verify`: **429 casos, cero fallos/errores/omitidos**, empaquetado y control JaCoCo superados.
- Flyway aplicó V54 en PostgreSQL 17.6 temporal. Se comprobó la migración esperada en el endpoint técnico y compatibilidad de sincronización existente.
- La revisión de integridad añadió detección de contradicciones en revisiones antiguas y rechazo de números de coma flotante antes de perder precisión. Ambas regresiones están probadas.
- `git diff --check` sin errores; cambio privado `backend-saas/docker-compose.dev.yml` conservado intacto.
- No se ejecutaron migraciones en BD de negocio, reinicios, commit, push ni despliegue. Backend local y frontend no cambiaron: su integración y validación pertenecen a las fases siguientes.

## Segunda fase: emisor local versionado

Alcance: preparar snapshots v2 desde el servicio local de documentos, utilizando la cola duradera existente. No habilitar consultas centrales, cambiar pantallas, reconstruir históricos ni desplegar servicios en esta fase.

- `DocumentSyncPublisher` reúne las peticiones dentro de la transacción de negocio. Publica una única instantánea final por documento en `beforeCommit`, incluyendo pagos y relaciones añadidos después de pedir el envío.
- La secuencia es `flush de negocio → bloqueo/incremento de revisión → refresh → copia del documento → outbox → flush`. El refresco posterior al bloqueo evita emitir una cabecera antigua si una transacción concurrente terminó mientras se esperaba la revisión.
- V243 añade `documento_sync_revision`, con una clave foránea al documento y revisión positiva. Se asigna desde 1 mediante UPSERT transaccional; no reutiliza ni altera `documento.version`. Ante rollback se revierten documento, revisión y outbox.
- Las revisiones de varios documentos se obtienen en orden estable. El buffer se suspende y restaura en `REQUIRES_NEW`; una transacción hija no hereda las peticiones del padre.
- El payload conserva las claves y los importes históricos del emisor anterior. Añade versión, revisión, actores, fechas, terminal de origen, vencimiento, liquidación por origen y relaciones salientes completas (`tipo`, `origenId`). Rechaza relaciones entre tiendas; no oculta una relación corrupta para enviar un snapshot incompleto.
- Confirmaciones, cobros y anulaciones mantienen sus puntos de emisión; edición administrativa permitida y relaciones de documentos confirmados también solicitan una nueva revisión. Una relación creada en borrador se envía al confirmar posteriormente.
- `contracts/sync/commercial-document-v2.json` es una fixture compartida: el productor real debe generar ese JSON y el receptor SaaS debe aceptarlo. Incluye precio unitario de tres decimales, importes finales de dos decimales, pago, cliente, actores y relación.

Los actores son los que ya constan en el documento: no se inventa el usuario de cada cobro ni se presenta el importe como deuda calculada. Las relaciones se conservan en el evento recibido; su proyección relacional para consultas corresponde a la siguiente fase.

### Evidencia de cierre de esta fase — 10/09/2026

- `backend/mvnw.cmd --batch-mode --no-transfer-progress verify`: **3.763 casos contabilizados, 3.756 aprobados, cero fallos/errores y 7 omitidos**, empaquetado Spring Boot superado en 7 min 52 s. Las omisiones son comprobaciones de enlaces simbólicos/permisos POSIX condicionadas por Windows, ajenas al emisor. No hubo pruebas Failsafe adicionales; los casos PostgreSQL se ejecutaron en Surefire mediante la BD temporal configurada.
- Nuevos tests locales: `DocumentSyncPublisherTest` (11), `DocumentSyncPayloadFactoryTest` (6), `DocumentSyncContractTest` (1) y `DocumentSyncPublisherPostgreSqlTest` (8): **26 casos aprobados, ninguno omitido**.
- Los 8 casos PostgreSQL demuestran coalescencia y estado final, revisiones consecutivas, rollback tras INSERT real de outbox, rollback antes del commit, aislamiento de `REQUIRES_NEW`, refresco de cabecera y pagos después de un cobro concurrente, primeras publicaciones simultáneas y rollback de ambos documentos cuando falla el segundo outbox.
- Adaptados los tests de `DocumentService`, promociones y manifiesto DEV. Los 2 casos reales existentes de `PaymentTerminalRefundDocumentPostgreSqlTest` pasan con el emisor v2 y comprueban revisión y relación con el documento original.
- `backend-saas/mvnw.cmd --batch-mode --no-transfer-progress -Dtest=CommercialDocumentSnapshotContractTest test`: **1 caso aprobado**. La misma fixture generada por la factory local se deserializa y valida en el receptor SaaS.
- Flyway aplica V243 en PostgreSQL 17.6 temporal, sin duplicados de versiones SQL ni cambios en migraciones anteriores. El manifiesto del artefacto DEV declara V243; no se crea ni promociona una nueva versión de producción.
- Revisión de `erp-code-review`: sin defectos demostrables en transacciones, aislamiento, contrato y puntos de emisión inspeccionados. La atomicidad y concurrencia se comprobaron además ejecutando las pruebas anteriores.
- `git diff --check` y comprobación equivalente de los archivos nuevos sin errores. El cambio privado de `backend-saas/docker-compose.dev.yml` conserva su hash anterior.
- Contenedor PostgreSQL efímero retirado al finalizar; no se ejecutaron migraciones en BD de negocio, reinicios de aplicaciones/backend, commit, push ni despliegue. No hay cambios de frontend ni de cálculos monetarios. Las consultas centrales, reconstrucción histórica e integración de las pantallas siguen pendientes.

## Tercera fase: consultas comunes internas

Alcance: servicio interno reutilizable y consultas SQL sobre snapshots recibidos. No se crean endpoints, permisos, pantallas, exportaciones ni nuevas dependencias. La empresa y las tiendas autorizadas deben ser suministradas por un futuro adaptador autenticado; `Scope` es el resultado de autorización, nunca un DTO enlazado directamente desde HTTP.

### Contrato para ampliar consultas

- `CommercialDocumentQuery` define un vocabulario tipado de ámbito, filtros, orden, periodos y dimensiones. El llamante debe elegir tipos y estados: no existe una definición oculta de venta o deuda.
- `CommercialDocumentReadService.page` devuelve hasta 200 documentos y un cursor de continuación. `CommercialDocumentReadRepository` aplica una sola construcción de filtros tanto al listado como a las agregaciones.
- `documentTotals` calcula en SQL todo el resultado filtrado, independientemente de la página. Admite día, mes, trimestre y año; dimensiones de tienda, cliente, creador y confirmador. Siempre separa tipo, estado y moneda y conserva los importes firmados. Son **sumas de documentos**, no ventas netas ni deuda.
- El actor local siempre se identifica por instalación y UUID. Elegir creador y confirmador a la vez agrupa por la pareja; no significa usuario del cobro.
- Un ámbito restringido sin tiendas produce cero resultados; los filtros solo pueden reducir el ámbito recibido, nunca ampliarlo.
- Los cursores están vinculados a empresa, ámbito, filtros y orden. La posición usa el valor de ordenación y desempates por tienda/documento. No son credenciales y no sustituyen la autorización. Tampoco congelan una fotografía histórica: una nueva revisión entre páginas puede cambiar la posición del documento.
- Se rechazan agregados de más de 2.000 grupos; se pide reducir intervalo o dimensiones, sin entregar importes parciales como si fueran completos.
- La lectura usa los índices de empresa/fecha, tienda/fecha y cliente local de V54. No se introducen índices especulativos para cada combinación; medir una consulta real antes de ampliarlos.

Para añadir un informe con filtros y dimensiones ya disponibles, el consumidor compone `Filter` y `Aggregation` y reutiliza el servicio, sin recorrer páginas ni copiar SQL. Un campo nuevo necesita contrato de origen, proyección aditiva si corresponde, filtro/dimensión tipada y pruebas de aislamiento. Una métrica nueva (por ejemplo ventas netas) requiere definir sus reglas y relaciones antes de exponerla; no se admite SQL o una fórmula arbitraria enviada por el cliente.

### Metadata y relaciones consultables

V55 añade vencimiento, anulación, liquidación por origen y conocimiento de relaciones a la cabecera; proyecta `FACTURA_DE`, `RECTIFICA` y `COMPENSA` en una tabla hija con dueño compuesto por empresa/tienda/documento. Solo la revisión ganadora sustituye cabecera y relaciones, en la misma transacción. Un origen puede llegar más tarde, por lo que su referencia no exige que ya esté proyectado.

Metadata ausente permanece desconocida. `relaciones: []` significa conjunto conocido vacío, mientras ausencia o `null` significa desconocido. Una nueva instantánea completa sin ese dato no conserva engañosamente las relaciones de una revisión anterior. No se reescribe V54 ni se reconstruye información histórica por inferencia.

### Limitaciones detectadas antes de integrar pantallas

1. **Cliente multitienda:** V53 tiene `customer_id UNIQUE` en `saas_customer_identity_link`, por lo que actualmente un cliente central solo tiene un vínculo de instalación. No hay distribución inbound de clientes implementada. La consulta une solo empresa + instalación de origen + cliente local y verifica la empresa del maestro central. Los documentos sin vínculo permanecen visibles sin identidad central resuelta; un filtro por cliente central solo incluye vínculos verificados. No garantiza todavía el historial completo del mismo cliente en varias instalaciones. Debe resolverse su distribución/vinculación explícita antes de prometer esa funcionalidad.
2. **Permisos:** los permisos administrativos actuales no autorizan por sí mismos a una instalación de tienda a consultar otras tiendas. El token de instalación tampoco identifica al usuario local. La nueva capa no amplía estos accesos.
3. **Ventas:** las consultas locales no tienen un único criterio de reconocimiento ticket/albarán/factura derivada. Por eso no se presenta una suma indiscriminada de tipos como ventas netas. El resumen anual de facturas sí dispone de las reglas ya confirmadas, que se aplicarán en su consumidor.
4. **Cobertura histórica:** solo se consulta lo recibido y proyectado como v2. Datos legados, vínculos ausentes y origen aún no recibido no se inventan ni se mezclan con la BD local.

### Evidencia de cierre de esta fase — 10/09/2026

- Implementados `CommercialDocumentQuery`, `CommercialDocumentCursor`, `CommercialDocumentReadService` y `CommercialDocumentReadRepository`. V55 y `CommercialDocumentQueryMetadata` amplían la proyección existente sin cambiar V54 ni el productor local.
- Pruebas focalizadas del contrato, metadata, cursor, servicio y proyector: **170 casos aprobados**, incluidos 17 de recepción PostgreSQL. Después, `CommercialDocumentReadPostgreSqlTest`: **28 casos aprobados** en PostgreSQL 17.6, todos los documentos de prueba pasando por el receptor real.
- La integración de lectura verifica ámbito empresa/tienda, intersección y ámbito vacío, vínculos correctos/tardíos/incoherentes, perfiles de otra empresa no expuestos, identidades repetidas por instalación, búsqueda literal, las diez combinaciones de orden/dirección, empates, los cinco periodos, límites inclusivos y bisiestos, dimensiones y totales exactos sobre más filas que la página.
- Los tests conservan diferencias de un céntimo incluso con cantidades superiores al entero exacto de JavaScript; no usan coma flotante. Revisiones repetidas y antiguas dejan una sola fila vigente y una sola contribución documental.
- `backend-saas/mvnw.cmd --batch-mode --no-transfer-progress verify`: **514 casos aprobados, cero fallos/errores/omitidos**, empaquetado Spring Boot y control JaCoCo superados en **1 min 48 s**. Ejecución completa sobre una segunda BD efímera limpia para evitar residuos de las pruebas focalizadas.
- Revisión independiente con `erp-code-review`: sin defectos demostrables en SQL, ámbito, identidades, keyset y agregación del alcance interno. Se documenta la limitación de paginar datos vivos: no equivale a una exportación con instantánea congelada.
- Flyway aplica V55 en pruebas; no se reescriben migraciones anteriores ni aparecen números duplicados. `git diff --check` y su equivalente para archivos nuevos pasan; el SHA-256 del cambio privado de `backend-saas/docker-compose.dev.yml` se conserva.
- No se ejecutaron migraciones en BD de negocio, reinicios, commit, push ni despliegue. Backend local y frontends no se modificaron ni recompilaron durante esta fase. No se afirma haber verificado rendimiento con millones de documentos ni impresión física, funcionalidades fuera de esta entrega.

## Cuarta fase: clientes y documentos centrales

### Decisiones aprobadas

- La creación de otro maestro con el mismo documento sigue rechazada. El usuario puede buscar por documento y elegir **Usar cliente existente**, revisar el perfil SaaS y crear una copia local vinculada. No se fusionan clientes antiguos automáticamente por coincidencia de NIF.
- Historial de tickets, facturas y albaranes, exportación y resumen anual consultan todas las tiendas de la misma empresa emisora y solo lo recibido en SaaS. Se mantienen los permisos locales efectivos por pestaña. Este acceso no amplía los informes operativos globales ni los comandos de cobro, deuda o stock.

### Implementación

- V56 permite varios vínculos de instalación con un mismo maestro central: mantiene la clave de instalación/cliente local y la unicidad de instalación/cliente central, e impide reasignar silenciosamente el propietario de un vínculo. Una reserva de adopción comprueba empresa, instalación, documento, cliente activo y revisión elegida; no modifica el perfil ni incrementa artificialmente su revisión.
- V244 conserva código central e intención duradera local. La ficha usa su código local; no copia saldos ni consentimiento comercial. Ficha, contador, outbox `CUSTOMER_ADOPTION` y confirmación local de la intención se guardan atómicamente. El receptor finaliza únicamente el vínculo reservado.
- La intención se prepara antes de abrir la transacción de escritura. Ante fallo se solicita cancelación después del rollback; el worker recupera intenciones abandonadas. El segundo clic relee la operación bajo bloqueo y reutiliza la ficha confirmada. No se retiene una conexión de escritura mientras se abre otra transacción independiente; el método rechaza una transacción externa.
- Las versiones de cliente administrativo pueden ser cero. Las revisiones HTTP deben ser enteros JSON: no se truncan fracciones ni se aceptan cadenas como versiones. La revisión documental conserva su contrato no negativo; el emisor local la asigna desde uno.
- `CustomerAdoptionController`: `POST /api/v1/customers/central-lookup` y `/adopt-central`. El control compartido `CentralCustomerReuse` está junto al documento en las altas de APP VENTA y APP GESTIÓN, con revisión explícita y protección frente a doble pulsación y respuestas obsoletas.
- `CommercialDocumentQueryController` expone `POST /api/v1/commercial-document-queries/page`, `/export` y `/annual` mediante token de instalación. El ámbito se construye a partir de la instalación autenticada y el vínculo exacto empresa/instalación/cliente local/cliente central; no se toma el NIF como autorización.
- `SaasCustomerDocumentController` adapta esas consultas bajo `/api/v1/customer-document-reports/saas`, usando credenciales de instalación exclusivamente en el backend y conservando los permisos del usuario local. No existe fallback silencioso a documentos locales.
- Se reutilizan el filtro y el servicio SQL común de la fase 3. La pantalla usa cursor, conserva ordenación, columnas configurables y carga por scroll; añade tienda y moneda. El identificador de fila incluye tienda y documento, evitando colisiones entre UUID de instalaciones distintas.
- El XLSX exporta todo el resultado filtrado, o las claves compuestas cargadas y su orden cuando no hay filtros, con límite explícito de 50.000 documentos. La lectura completa central usa una instantánea transaccional consistente; no devuelve resultados recortados como si estuvieran completos. Conserva datos del cliente, filtros, columnas elegidas, estilo neutro y totales separados por moneda.
- Los importes viajan como cadenas decimales. El frontend los presenta sin convertir el entero completo a `Number`; el XLSX conserva como texto los importes que Excel no puede representar exactamente, incluido su total, sin dejarlos fuera de una suma numérica engañosa.
- El PDF anual reutiliza Jasper y las reglas ya aprobadas, sin recalcular documentos. La plantilla actual es EUR: una moneda distinta produce error explícito, nunca conversión o suma entre monedas.
- V57 añade nombres históricos opcionales de terminal y actor para su presentación. Los UUID siguen siendo las identidades de origen. Los snapshots sin etiquetas no inventan nombres ni muestran UUID como nombre de usuario.
- Los mensajes nuevos y los estados de fallo están disponibles en ES/EN/ZH. No hay nuevas dependencias ni cambios en cálculos de venta o fiscalidad.

### Validación de la cuarta fase

- SaaS completo: **560 pruebas, cero fallos, errores u omitidas**, empaquetado y JaCoCo aprobados en PostgreSQL 17.6 aislado (2 min 03 s). Incluye HTTP autenticado, aislamiento, adopción, revisiones estrictas, selección multitienda, once órdenes, exportación consistente y agregación anual.
- Frontend completo: **225 archivos, 2.228 pruebas aprobadas**. Tras situar la reutilización junto al documento, 20 pruebas focalizadas adicionales aprobadas. Compilaciones APP VENTA y APP GESTIÓN y presupuesto del bundle aprobados; VENTA queda cerca del límite JavaScript: 798.577 / 800.000 bytes. No se elevó el presupuesto.
- Revisión visual con componentes reales y respuestas ficticias en navegador aislado: ES/ZH, alta con revisión del perfil, uso del cliente, cabeceras, foco y carga de 50 a 70 filas por scroll. No equivale a una prueba end-to-end completa contra servicios desplegados.
- PDF Jasper ES/ZH revisado renderizado con datos de prueba; XLSX reabierto con POI y renderizado para revisar cabecera, estilo y totales por moneda. Se comprueban celdas de texto contra fórmulas y precisión de importes. No se verificó impresión física.
- Revisión independiente de API y refactor transaccional: corregidos incompatibilidades de contrato y retención de conexiones. La integración real mantiene un pool de dos conexiones para demostrar concurrencia de adopción, no oculta el fallo ampliándolo.
- Backend local: **3.831 casos finales, 3.824 aprobados y 7 omitidos por restricciones Windows de enlaces simbólicos/permisos POSIX; cero fallos y errores pendientes**. La ejecución completa inicial duró 7 min 15 s y detectó tres grupos con problemas de configuración: faltaba importar el resolver real en la prueba de devoluciones y dos grupos rechazaron correctamente el nombre no permitido de la BD temporal. Tras corregir el import y usar `tpv_erp_test` en el mismo PostgreSQL efímero, se repitieron únicamente esos grupos con `verify`: **7 casos aprobados, empaquetado aprobado en 58 s**. El recuento final combina los informes completos con esa repetición, no afirma una segunda ejecución completa. Failsafe no tenía casos adicionales; la integración PostgreSQL se ejecutó mediante Surefire.
- Flyway aplica V244 local y V57 SaaS en aislamiento; no hay versiones SQL duplicadas. El manifiesto DEV declara V244, sin promocionar una versión fiscal de producción ni reescribir migraciones previas.
- `git diff --check` y comprobación equivalente de los 60 archivos nuevos del alcance pasan. El cambio ajeno `backend-saas/docker-compose.dev.yml` conserva SHA-256 `198FF6B6FC4CDA56C6AA6E4F4EF449F6E28F05BCFCEDD64B33F4CC78B5E62F0D`. No se hizo commit, push ni reinicio de servicios de negocio.
- Se cerraron el navegador y el servidor visual temporales y se retiró únicamente el contenedor PostgreSQL de esta fase con su volumen de datos ficticios. Los diagnósticos y renders permanecen en rutas `output/playwright` ignoradas por Git.

### Corrección posterior de F7 — 10/09/2026

- El historial conserva la fila seleccionada y el desplazamiento al volver de editar el cliente en la primera página, sin suprimir la actualización de los datos. Si esa fila desaparece, selecciona una fila válida. La paginación posterior mantiene su comportamiento existente.
- Cambiar cliente, sesión, pestaña, filtros o acceso reinicia el conjunto. Se ignoran las respuestas de peticiones abortadas. Si el servidor rechaza permiso, vínculo o contexto, se retiran los datos anteriores.
- La revisión independiente detectó y cerró una regresión del reintento tras invalidar una tabla paginada: tanto Reintentar como volver de F7 parten de la primera página, sin conservar un cursor huérfano ni iniciar reintentos automáticos al recibir el error.
- Validación: **60 pruebas del diálogo y 5 de integración con `PartyDirectoryPanel` aprobadas**, compilaciones APP VENTA y APP GESTIÓN aprobadas, presupuesto del bundle dentro de los límites y revisión independiente sin defectos demostrables pendientes en esta corrección. No se repitieron las suites backend porque no cambió código backend.
- Archivos de código modificados en esta corrección: `CustomerDocumentsDialog.tsx` y su test. Sin cambios de traducciones, estilo, esquema o datos de negocio; sin reinicios, commit ni push.

### Límite de esta entrega

Al cerrar la cuarta fase, los adaptadores y pantallas quedan preparados en código; no se han reiniciado servicios ni desplegado las migraciones. Los datos anteriores sin snapshot v2 y los clientes antiguos sin vínculo no se reconstruyen o asocian por inferencia. La decisión posterior sobre adaptación puntual DEV se documenta debajo; su ejecución y la activación ordenada siguen pendientes. Los informes operativos locales y su semántica de venta/deuda no se sustituyen.

La minimización de datos y el acceso restringido siguen la separación local/central acordada; referencia general oficial: [AEPD, protección de datos por defecto](https://www.aepd.es/derechos-y-deberes/cumple-tus-deberes/medidas-de-cumplimiento/proteccion-de-datos-por-defecto). Las pruebas técnicas no constituyen por sí solas una certificación normativa o de producción.

## Implantación y recuperación

### Adaptación puntual de desarrollo — decisión aprobada e implementada

El usuario eligió conservar y adaptar los documentos de desarrollo (opción 2). No se necesita un subsistema permanente de migración histórica ni trabajo al arrancar:

- El emisor `HttpSyncEventSender` descarta el cuerpo de la respuesta y considera correcto cualquier HTTP 2xx. `SyncEventService` puede aceptar un evento como `IGNORED` si no dispone de un proyector compatible. Por tanto, **encolado**, **enviado** y **proyectado/consultable** son estados distintos; los contadores globales de envío no demuestran cobertura histórica.
- Antes de enviar el histórico a servicios reales, comprobar la versión del receptor y un lote pequeño proyectado. La conciliación debe correlacionar documento, evento y revisión; no basta con observar que la cola está vacía. Los eventos ya ignorados no se reproyectan simplemente reenviando su mismo identificador.
- La operación técnica manual requiere ADMIN, perfil `dev` y habilitación expresa. Se limita a la instalación/tienda actual y a un período explícito, con corte fijo y lotes de hasta 100 originales sin revisión local v2. No se ejecuta al arrancar, consultar ni activar el worker.
- Se reutilizan `DocumentSyncPublisher`, `documento_sync_revision` y el outbox: primera revisión y snapshot quedan en la misma transacción, sin tabla nueva de trabajos. Un claim atómico impide duplicados ante un reintento o una publicación normal concurrente. No confirma documentos, recalcula importes, repite cobros o movimientos de stock, reasigna instalaciones ni asocia clientes por coincidencia de NIF.
- Respetar la cuota SaaS de eventos y la cola operativa existente. Un lote fallido no debe adelantar el cursor ni saltarse documentos bloqueados. La finalización local solo acredita que se han encolado snapshots; la verificación central es una etapa separada.

Implementados `/api/v1/sync/document-recovery/{preview,prepare,verify}`, la consulta técnica central `recovery-status` y `tools/document-sync-recovery.ps1`. La simulación y verificación no escriben; la preparación encola y audita. Un evento enviado no se considera verificado sin comprobar instalación, evento, revisión registrada y vínculo de cliente en SaaS. No se añaden migraciones en esta adaptación. Plan, validaciones y límites en [document-sync-oneoff-recovery.md](document-sync-oneoff-recovery.md); procedimiento en [tools/document-sync-recovery.md](../tools/document-sync-recovery.md).

Antes de ejecutar con datos del usuario quedan por concretar instalación, período, copia de seguridad, despliegue compatible y comprobación de un lote pequeño. No se ha realizado esa ejecución durante la implementación.

La adaptación es aditiva: crea el snapshot y su registro de revisiones, sin modificar documentos históricos. Se prepara en código y se prueba en BD aislada; no se ejecuta sobre servicios reales en esta fase. Desplegar primero el receptor compatible y después productores v2. Las consultas muestran solo lo recibido en SaaS, sin avisos operativos de envíos pendientes; no anunciar cobertura histórica completa sin verificarla.

Ante un fallo de proyección se revierte la transacción y el outbox local conserva el evento para reintento. No se marcan como correctos datos ambiguos. El rollback de aplicación no requiere borrar eventos ni reescribir migraciones aplicadas.

## Decisiones pendientes para fases posteriores

- Concretar la atribución de cada informe por usuario: vendedor/confirmador del documento o usuario que cobra una deuda. Se conservarán identidades distintas; no se equipararán silenciosamente.
- Los permisos del historial central de un cliente y su reutilización multitienda quedaron aprobados en fase 4. Cualquier informe global nuevo o reconciliación masiva de clientes antiguos requiere su propio ámbito y decisión; no hereda acceso ilimitado de este historial.
- Antes de añadir ventas netas, acordar reconocimiento de ticket/factura y albarán/factura, tratamiento de anulación y sus recuentos; no copiar las variantes locales divergentes.
- Cualquier adaptación DEV adicional requiere otro ámbito autorizado. La carga de agosto de tienda 001 ya está verificada; no implica cobertura de otros períodos, instalaciones o datos sin vínculo central.
