# Importador Excel — acciones separadas y altas manuales

Fecha: 2026-09-07. Continuación del plan aprobado. Esta entrega es una fase implementada y probada de forma focalizada, no la aceptación final del rediseño completo.

## Trabajo realizado

- Aplicar lee y clasifica; no inicia altas, actualizaciones de maestros ni traslado al destino. Eliminado también el antiguo efecto de alta automática al aplicar con una cuadrícula proporcionada.
- Productos no importables dispone de Añadir productos. La modalidad automática pide confirmación y utiliza el lote transaccional del backend. La modalidad manual reutiliza ProductCreateDialog, abre el siguiente artículo después de guardar y permite cancelar dejando las altas anteriores.
- La asociación fila original → producto creado se conserva incluso si el usuario cambia el código en el formulario. El código y nombre originales del Excel no se sustituyen por lo escrito en el alta.
- Precio de compra distinto dispone de actualización exclusiva de compra, condicionada a su casilla. Etiquetas ES/EN/ZH solicitadas.
- Productos importables incluye existentes con precio diferente y altas completadas. Separa Actualizar atributos marcados de Importar al documento / Importar a edición masiva.
- Se pueden trasladar existentes aunque queden productos pendientes de alta. Backend vuelve a validar los errores relevantes para cada operación.
- Las operaciones que guardan fichas piden confirmación, refrescan la revisión y no importan automáticamente al destino.
- Tras una escritura confirmada, un fallo al refrescar se comunica como actualización ya guardada y revisión pendiente; no se anuncia falsamente un rollback.
- Importar al destino prepara las líneas sin actualizar maestros. Almacén no confirma el documento.
- Cantidad se oculta y excluye de la configuración efectiva de Stock. Pruebas backend confirman que las asignaciones antiguas, valores inválidos y diferencias entre duplicados de esa columna se ignoran; Cantidad por paquete sigue intacta.
- Almacén conserva la cantidad normalizada/acumulada del backend, incluso cuando no existe una columna Cantidad asignada.
- Stock utiliza los valores y versión de producto de la revisión reciente, sin reutilizar maestros obsoletos de la página. Conserva la información de existencias cargada en ella.
- Auditoría por operación con recuentos de escrituras efectivamente completadas: preparar destino y los lotes fallidos registran cero altas/actualizaciones.
- Campos normales de columna de 52 px, ayuda breve, retirada del botón superior Actualizar ambiguo y textos nuevos ES/EN/ZH. Se ha seguido frontend-design manteniendo el estilo ERP, sin elementos decorativos ni dependencias.
- Se evita una búsqueda repetida por cada fila para determinar diferencias de compra.
- La unificación Familia / Subfamilia de la fase anterior permanece intacta.

## Archivos modificados

Archivos editados en esta continuación, preservando sus cambios anteriores:

- [ProductExcelImportApplyService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyService.java>)
- [ProductExcelImportOperationsTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportOperationsTest.java>)
- [ProductExcelImportPreviewServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewServiceTest.java>)
- [productExcelImports.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/api/productExcelImports.ts>)
- [SharedExcelImportDialog.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx>)
- [SharedExcelImportDialog.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/SharedExcelImportDialog.test.tsx>)
- [SharedExcelImportManual.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/SharedExcelImportManual.test.tsx>)
- [excelImport.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/excelImport.ts>)
- [excelImportProductForm.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/excelImportProductForm.ts>)
- [StockScreen.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/StockScreen.tsx>)
- [StockScreen.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/StockScreen.test.tsx>)
- [WarehouseDocumentDialog.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx>)
- [WarehouseDocumentDialog.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx>)
- [SharedManagementMessages.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/i18n/SharedManagementMessages.ts>)
- [shared-excel-import.css](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/styles/shared-excel-import.css>)

- Este informe: docs/excel-import-separated-actions-2026-09-07.md.
- Retirado únicamente el informe JSON temporal de Vitest generado durante esta sesión, frontend/excel-import-tests.json; se puede regenerar ejecutando el reporter JSON.
- No se ha modificado el cambio ajeno de backend-saas/docker-compose.dev.yml ni se han creado commits.

## Decisiones técnicas

- Reutilización del lector, preview, escritor transaccional y formulario de productos existentes; no se añaden tablas, migraciones, librerías ni endpoints.
- Se amplía el contrato existente de /apply mediante operation y expectedPreviewFingerprint. Las acciones son CREATE_MISSING, UPDATE_PURCHASE_PRICE, UPDATE_SELECTED_FIELDS y PREPARE_DESTINATION. El camino antiguo sin operation se conserva por compatibilidad.
- La huella vincula cada operación con la revisión vigente. Después se revalidan atributos pertinentes y versiones; la escritura sigue en el componente transaccional.
- Las altas manuales son individuales. La cola pertenece al importador compartido, no a Almacén, para mantener un único comportamiento en Stock y Almacén.
- resolvedProducts permite mantener la asociación con las filas originales; no reescribe datos Excel.
- La preparación de Stock valida los atributos seleccionados para su borrador, pero envía comandos sin modificación al escritor. Las acciones sobre fichas no se bloquean por una cantidad de documento irrelevante.
- Mostrar solo valores nuevos afecta a la presentación del resumen, no impide la consulta interna necesaria para resolver productos y concurrencia.
- Se mantienen los permisos backend, la política de precios cero y la actualización de proveedor-producto al confirmar el documento.

## Validaciones realizadas

- Backend: 127 pruebas focalizadas, todas correctas y ninguna omitida:
  - ProductExcelImportOperationsTest: 8.
  - ProductExcelImportPreviewServiceTest: 51.
  - ProductExcelImportApplyServiceTest: 34.
  - ProductExcelImportControllerTest: 20.
  - ProductExcelImportSummaryServiceTest: 14.
- Frontend: 183 pruebas correctas en 7 archivos: SharedExcelImportDialog, SharedExcelImportManual, WarehouseDocumentDialog, StockScreen, productExcelImports, SharedManagementMessages y gestionI18nGuard.
- Cubiertos: acciones separadas, revisión obsoleta, compra desmarcada, ausencia de escrituras al preparar destino, auditoría sin mutaciones ficticias, secuencia manual y cancelación con cambio de código, incorporación de altas, Stock sin Cantidad y cantidad acumulada en Almacén.
- Compilaciones TypeScript/Vite de APP VENTA y APP GESTIÓN correctas después del último cambio funcional.
- Presupuesto del bundle dentro de los límites; VENTA mantiene aviso de proximidad en JS y CSS.
- git diff --check correcto.
- Las pruebas de cola manual utilizan el contrato del formulario y API simulados; las del escritor y error transaccional no demuestran rollback contra PostgreSQL real.
- No se han realizado escrituras contra una BD de negocio ni publicado cambios.

## Riesgos detectados

- El rediseño completo NO está terminado. Existen partes de UI y exportación todavía pendientes.
- Falta comprobar el flujo real con el formulario completo, ficheros XLS/XLSX y BD aislada, incluyendo fallos entre escrituras, concurrencia y permisos.
- La auditoría sigue siendo best effort como en el servicio existente: un fallo de auditoría posterior al commit no revierte ni debe presentar como fallida una escritura ya guardada.
- Las advertencias de jsdom por navegación de exportaciones y de Mockito por instrumentación no impidieron las pruebas; no equivalen a una comprobación visual o de descarga en navegador real.
- La compilación de VENTA sigue próxima a sus límites de tamaño.

## Trabajo pendiente

- Sustituir los cuatro controles especiales por un único desplegable editable por atributo. Actualmente siguen separados origen y valor/columna.
- Completar tablas organizadas con columnas independientes por atributo y valores actuales/nuevos, conservando filas originales y navegación eficiente con miles de filas.
- Completar Exportar XLSX en todas las pestañas, incluida la cuadrícula editada. Las exportaciones no-resumen todavía conservan el mecanismo anterior CSV.
- Ajustar el resumen/exportación para que reproduzcan exactamente la modalidad y todas las columnas visibles.
- Revisar visualmente contraste, foco, scroll horizontal, arrastre, redimensionado y resoluciones habituales.
- Ejecutar E2E con XLS/XLSX reales y BD aislada, permisos combinados, doble pulsación/confirmación, concurrencia, rollback real de altas y actualizaciones, proveedor y confirmación del documento.
- Ejecutar suites globales y aceptación integral; las pruebas de esta fase son focalizadas.

## Próximos pasos recomendados

Continuar con los cuatro desplegables editables y las tablas/exportaciones por pestaña. Después, ejecutar el flujo completo contra BD aislada y revisar la interfaz real. No declarar terminado el importador hasta cerrar estos pendientes.
