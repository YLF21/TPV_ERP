# Historial documental desde Clientes — 2026-09-09

## Implementado

- Directorio de Clientes compartido por APP VENTA y APP GESTIÓN: clic selecciona;
  doble clic o activación con Intro/Espacio abre el historial, no el editor.
- F1 Tickets, F2 Facturas (incluidas rectificativas), F3 Albaranes, F7 formulario
  existente de modificación, Esc cerrar. Al volver del editor se conserva la pestaña.
- Consulta de la tienda activa, inicialmente todos los estados y fechas, más recientes
  primero. Carga incremental de 50 documentos al hacer scroll, sin anterior/siguiente.
  Las filas ya cargadas y su selección se conservan; renderizado acotado al área visible.
  No se usa el listado de deudas.
- Ordenación ascendente/descendente en las nueve cabeceras mediante TableSortButton.
  Búsqueda parcial literal por número, estado y fechas inclusivas; todo se consulta
  en servidor antes de paginar. Tipo/estado ordenan por su código almacenado.
- Exportar a Excel: con búsqueda o filtros aplicados, todo el resultado filtrado;
  sin filtros, exactamente los IDs cargados en la tabla y en ese orden. Ordenar no
  cuenta como filtrar. Las ediciones de filtros sin aplicar bloquean exportar.
- Cabecera separada del área de desplazamiento, tabla que ocupa el espacio disponible
  incluso vacía, columnas redimensionables, selección y foco de teclado, ES/EN/ZH.
- Cabeceras con los controles de Stock: botones al pasar el ratón o recibir foco,
  menú de ordenación/movimiento, arrastre y Ctrl+flechas para reordenar columnas.
  Orden y anchuras persistidos mediante `useTableLayoutPreference` con clave
  `customers.documents`, por usuario y aplicación, común a las tres pestañas.
  Las columnas del Excel siguen ese orden. Escape cierra primero el menú abierto.
- Se conservan los permisos documentales y de edición. Las pestañas sin permiso
  están deshabilitadas. Consultar el historial no escribe datos de negocio.
- Proveedores, miembros y selector de cliente de la pantalla de cobro no cambian.

## Contrato y archivos

`GET /api/v1/document-reports/{tickets|invoices|delivery-notes}` acepta
`customerId` opcional, además de `limit` y `cursor`. Para el historial admite
`search` (máximo 120 caracteres), `status`, `dateFrom`, `dateTo`, `sortBy` y
`sortDirection`; requieren cliente. Respuesta existente: `{items,nextCursor,hasMore}`.
Sin filtros nuevos, comportamiento anterior. El cursor nuevo está vinculado a
cliente, tienda, tipos, filtros y orden; cambiar estos parámetros reinicia la carga.

`POST /api/v1/customer-document-reports/export.xlsx`: recibe `customerId`, `reportKey`,
`filters`, `sortBy`, `sortDirection`, `columns`, `labels` traducidas y, solo sin
filtros, `documentIds`. Los filtros vacíos se omiten. Vuelve a comprobar permisos por
tipo y pertenencia de todos los documentos; nunca exporta silenciosamente un subconjunto.
Máximo 50.000 filas, con error explícito `customer_documents_export_limit_exceeded`.
POI SXSSF, proyección escalar por lotes de 500 y snapshot REPEATABLE_READ; ninguna
escritura de negocio, solo auditoría de exportación realizada. Cabecera gris neutra,
filas de identificación/cabecera inmovilizadas, autofiltro, fechas e importes numéricos;
texto recibido nunca como fórmula.

### Datos del cliente, filtros y suma del XLSX

- Código, NIF/número de documento y nombre se obtienen de la ficha validada por
  empresa en el backend, no de valores enviados por el frontend. Identificadores
  escritos como texto para preservar ceros iniciales y evitar fórmulas inyectadas.
- Cabecera: tres filas de datos del cliente, una con los filtros efectivos
  (búsqueda normalizada, estado y fechas inclusivas) o «Sin filtros», y los nombres
  de columnas. Las etiquetas nuevas se envían en ES/EN/ZH; los clientes antiguos
  que las omitan siguen admitidos, con etiquetas predeterminadas en español.
- Estilo en blanco, negro y gris claro. El orden de columnas sigue siendo el de la
  tabla visual. El autofiltro solo incluye cabecera y documentos, nunca metadatos
  ni la suma final.
- «Total documentos» suma los importes persistidos de todas las filas exportadas,
  incluidos los negativos, mediante acumulación BigDecimal entre páginas. No cambia
  documentos, estados, precios ni cálculos fiscales. No es un saldo pendiente.
- Si la columna Total se exporta, el pie contiene SUM sobre el rango de documentos
  y su resultado numérico almacenado, también para lectores sin recálculo inmediato.
  La fórmula sigue la posición de esa columna al reordenarla. Una selección vacía
  muestra cero; una selección de columnas sin Total conserva la suma numérica en el pie.
- Nombre predeterminado del archivo: `código-nombre-tipodoc.xlsx`, tanto en el
  diálogo de guardado de escritorio como en la descarga del navegador. Se utiliza
  el cliente del historial y el nombre traducido de la pestaña (Tickets, Facturas
  o Albaranes en español). Se preservan acentos y chino; se sustituyen caracteres
  no válidos para Windows y se acota cada componente a 80 caracteres.
  Verificación del nombre: 37 pruebas frontend verdes, incluidas las tres pestañas,
  ES/EN/ZH, escritorio/navegador y nombres largos; builds VENTA/GESTIÓN,
  presupuesto de tamaño y `git diff --check` correctos. Sin cambios de backend.

Cliente validado en empresa actual; consulta restringida a tienda autenticada,
cliente y tipos autorizados. Un filtro vacío/inválido no se omite silenciosamente.
Primero se paginan IDs en SQL; luego se cargan pagos y líneas por separado.

Frontend principal: `CustomerDocumentsDialog.tsx`, `CustomerDocumentsDialog.css`,
integración en `PartyDirectoryPanel.tsx`, catálogos MessagesEs/En/Zh y guard i18n.
Backend: `CommercialDocumentRepository`, `TicketReportController/Service`,
`DocumentReportController/Service` y sus pruebas.
Nuevos: `CustomerDocumentReportFilter`, `CustomerDocumentReportQueryRepository` y
`excel/CustomerDocumentExportRequest`, `CustomerDocumentExcelExportController/Service`.

## Verificación inicial del historial

- Frontend: 33 pruebas verdes en CustomerDocumentsDialog, integración del directorio
  en ambas aplicaciones, PartyDirectoryPanel y guard ES/EN/ZH. Cubren F1/F2/F3/F7,
  permisos, paginación, cancelación de respuestas antiguas, error/reintento, edición
  y retorno al cliente actualizado, proveedores sin regresión y ausencia de escrituras
  durante consulta. Rechazo de filas ajenas si un backend antiguo ignora el filtro.
- Backend: 44 pruebas verdes; 3 ejecutadas en PostgreSQL 17.6 aislado. Verifican
  tickets normales pagados, todos los estados, orígenes facturados, grupos de tipos,
  aislamiento por cliente/tienda, cursor y límite SQL antes de cargar colecciones.
- Prueba visual con componente real y respuestas sintéticas: 1392×844 con documentos,
  1024×768 sin documentos, F7 con foco en formulario y regreso a la pestaña anterior.
  Resize por teclado de 210 a 218 px y por puntero de 218 a 248 px.
- Compilaciones APP VENTA/GESTIÓN correctas y `git diff --check` sin errores.
- Presupuestos: VENTA JS 788437/800000 bytes, CSS 450642/460000; GESTIÓN JS
  393567/600000 y CSS 450642/520000. VENTA sigue cerca del límite, sin superarlo.
- El contenedor y la BD sintética de pruebas se retiraron. No se modificaron migraciones,
  BD de negocio ni el cambio ajeno en `backend-saas/docker-compose.dev.yml`.

## Verificación de ordenación, filtros, scroll y XLSX

- Frontend: 27 pruebas verdes (20 del diálogo, 5 de integración del directorio,
  2 del guard ES/EN/ZH). Cubren filtros, nueve órdenes, scroll sin duplicados,
  error y reintento sin perder filas, filas virtuales, permiso revocado, ambas
  modalidades XLSX, filtros pendientes, doble pulsación, cancelación de exportación
  y restauración del desplazamiento virtual al volver de F7.
- Backend: 113 pruebas focales de API, servicios, autorización y generador XLSX;
  25 casos de PostgreSQL real aislado prueban todos los órdenes y cursores,
  filtros anteriores a paginación, columnas del XLSX y exclusión efectiva de
  documentos de otros clientes, tiendas o tipos. Pruebas del generador cubren
  563 filas multipágina, límite 50.000, fechas/importes y texto sin fórmulas.
  Última revalidación de 10 casos tras corregir el cierre SXSSF: verde, sin avisos
  POI ni nuevos temporales. Contenedor PostgreSQL sintético retirado. XLSX de prueba
  regenerado en `backend/target/customer-documents-export-preview.xlsx`.
- Navegador con componentes reales y datos sintéticos, sin escrituras de negocio:
  scroll 50 → 100 → 123 filas, manteniendo solo 23 filas montadas; filtro Pendiente
  devuelve 41, ordenación por número reinicia scroll, tabla vacía conserva el espacio.
  Revisadas resoluciones 1392×844 y 1024×768, contraste, foco y cabeceras fijas.
  Evidencias: `output/playwright/customer-documents-20260909/filters-1392-final.png`,
  `pending-1024.png`, `empty-filtered-1024.png`.
- Builds APP VENTA y APP GESTIÓN correctos. Presupuesto VENTA JS 790581/800000
  y CSS 450642/460000; GESTIÓN JS 395044/600000 y CSS 450642/520000.
- Sin dependencias nuevas, migraciones, cambios en importes/estados ni escrituras
  en documentos reales. El SHA-256 del docker-compose ajeno sigue intacto.

## Significado de los estados, sin modificaciones

- Un ticket normal cobrado se conserva como CONFIRMADO. No significa deuda pendiente.
- Los documentos cobrables (facturas, albaranes y tickets cuentaCobrar) usan PENDIENTE
  si no hay cobros, PARCIAL si se ha cobrado una parte y PAGADO cuando se han liquidado.
- BORRADOR todavía no está confirmado; una venta aparcada es otra entidad y no entra
  en este historial. El usuario confirmó que los documentos pendientes sí aparecen.
- No se modifican estas reglas, no se recalculan importes ni se vuelven a emitir documentos.

## Verificación de cabeceras y activación de la ampliación

- 43 pruebas frontend verdes: historial, entrada desde ambos directorios, cabeceras
  compartidas, persistencia de preferencias y guard ES/EN/ZH. Revalidación de los
  23 casos del diálogo tras conservar el foco durante los movimientos: verde.
- Se comprueba movimiento desde menú, arrastre, Ctrl+flechas, anchura persistente,
  separación por usuario/aplicación, cierre por Escape y orden de columnas exportado.
  Reordenar no vuelve a consultar los documentos ni escribe datos de negocio.
- Revisión en navegador con componente real y API sintética: controles ocultos en
  reposo, visibles con hover/foco, arrastre, movimiento consecutivo con teclado y
  conservación del orden al reabrir y Escape del menú sin cerrar el historial.
  Revisadas resoluciones 1392×844 y 1024×768. Evidencias: `header-hover.png` y
  `header-final-1024.png` en el directorio
  `output/playwright/customer-documents-20260909/`.
- Builds VENTA/GESTIÓN y presupuesto de tamaño correctos; se conserva el aviso
  previo de proximidad al límite de VENTA. Sin nuevas dependencias ni migraciones.
- Backend reiniciado el 09/09/2026 a las 18:23 (Canarias), PID 98584, puerto 8080,
  perfiles `dev,fiscal-dev,saas-dev`. Health `UP`; OpenAPI ya incluye filtros del
  historial y `/api/v1/customer-document-reports/export.xlsx`.
- Flyway: 237 migraciones validadas, esquema V241, ninguna migración necesaria.
  Logs: `C:/Users/YLF/AppData/Local/Temp/tpv-backend-customer-headers-20260909-182302/`.

El frontend rechaza cualquier página que incluya documentos de otro cliente.
No se hicieron commits, push, cobros, impresiones ni modificaciones de documentos reales.

## Verificación de identidad, filtros y total del XLSX

- 34 pruebas frontend verdes: 27 del diálogo, 5 de integración del directorio y
  2 del guard ES/EN/ZH. Las etiquetas se envían traducidas y los datos personales
  se obtienen en backend, sin reenviarlos desde el navegador.
- 46 pruebas backend verdes: 17 del generador, 28 del contrato HTTP y una integración
  con PostgreSQL 17.6 aislado. Se comprueban compatibilidad de etiquetas antiguas,
  filtros efectivos, identificación como texto, permisos y aislamiento, 563 filas,
  suma con negativos, selección vacía y columnas reordenadas o sin Total.
  Se corrigió la expectativa del rango de una celda: POI lo serializa como `A5`.
- XLSX sintético generado por el servicio real, importado y renderizado con la
  herramienta de hojas de cálculo: nueve columnas, identidad del cliente, filtros
  de septiembre, formato gris y total 97,10. Fórmula comprobada mediante recálculo
  en memoria: 97,10 → 98,10 → 97,10 al variar y restaurar un importe; sin errores
  de fórmulas. Evidencia visual: `output/customer-documents-export-20260909/after.png`.
  Esta revisión no equivale a una impresión física ni a abrirlo en Microsoft Excel.
- Builds de APP VENTA y APP GESTIÓN y presupuesto de tamaño correctos:
  VENTA JS 791517/800000 bytes, CSS 450642/460000; GESTIÓN JS 395668/600000,
  CSS 450642/520000. Se mantiene el aviso previo de proximidad al límite.
- `git diff --check` correcto. El contenedor PostgreSQL sintético se ha retirado;
  no se han modificado datos de negocio, dependencias ni migraciones. El hash del
  `backend-saas/docker-compose.dev.yml` ajeno se conserva intacto.
- La comprobación del contrato en ejecución detectó una colisión de los nombres
  genéricos `Labels`, `Filters` y `Column` con otros informes en OpenAPI. Se asignan
  nombres de esquema exclusivos a los cuatro registros anidados de esta exportación,
  sin cambiar el JSON recibido ni su comportamiento. Compilación posterior correcta.
- Activación final: backend reiniciado el 09/09/2026 a las 18:59 (Canarias), PID
  104012, puerto 8080, perfiles `dev,fiscal-dev,saas-dev`, health `UP`. OpenAPI
  verificado en ejecución: identidad, total y filtros presentes, y referencias a los
  esquemas exclusivos correctas. Flyway valida 237 migraciones, esquema V241,
  sin ninguna migración necesaria. Logs en
  `C:/Users/YLF/AppData/Local/Temp/tpv-backend-customer-xlsx-20260909-185820/`.

## Hallazgo independiente, no modificado

`SalesDocumentDetailController` permite la ruta `/{documentId}/detail` con
INVOICES_READ o DELIVERY_NOTES_READ, pero no comprueba el permiso contra el tipo
real cargado; tampoco admite TICKETS_READ exclusivo. `DocumentService.findDetailed`
sí aísla por tienda. Conviene revisar esa autorización por tipo en otra tarea.
El historial implementado no utiliza esa ruta ni añade acciones de detalle o impresión.
