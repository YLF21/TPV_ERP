# Importador Excel — aceptación del rediseño

Fecha: 7 de septiembre de 2026.

Este informe consolida el plan actualizado y la unificación de Familia/Subfamilia. Sustituye las listas de pendientes de los informes de fases anteriores; estos se conservan como historial. Estado: implementación y aceptación local completadas, incluidas operaciones reales sobre BD de pruebas. No se ha publicado ni desplegado.

## Trabajo realizado

| Área | Comportamiento implementado |
| --- | --- |
| Lectura | XLS/XLSX reales mediante POI; primera hoja, firma y hash, límites, resultados guardados de fórmulas sin evaluarlas, rechazo de cifrado/macros/corrupción. |
| Configuración | Tres grupos, columnas de 52 px, letras mayúsculas AA+, cuatro desplegables editables columna/valor fijo, ayuda breve y fechas DD-MM-AA/DD-MM-AAAA. Familia/Subfamilia se asigna mediante un único código operativo de 3 o 6 dígitos. |
| Acciones | Aplicar solo organiza/valida. Añadir productos, actualizar compra, actualizar atributos y trasladar al destino son operaciones separadas. Las escrituras requieren confirmación. |
| Revisión | Columnas independientes, datos actuales/nuevos, filas originales, detalles completos de error, cabeceras fijas, resize con teclado/puntero, scroll horizontal/arrastre y renderizado de una ventana de filas. |
| Resumen | Incluye existentes, inexistentes y errores. Mostrar solo valores nuevos afecta exclusivamente a este resumen y su XLSX; desmarcado muestra BD actual y Excel nuevo. |
| Altas | Automáticas en un lote atómico; manuales reutilizan el formulario existente, guardar abre el siguiente y cancelar conserva lo ya guardado. La asociación de filas no modifica los valores originales del Excel. |
| Actualizaciones | Compra modifica exclusivamente compra y requiere su casilla. Actualizar atributos respeta campos marcados, permisos, versiones y política de no reemplazar precios persistidos con cero. |
| Stock | Cantidad excluida de configuración, validación, agrupación y destino, también en backend/configuraciones antiguas. La columna permanece en la cuadrícula original. No afecta a cantidad por paquete ni mínimos/máximos. |
| Almacén | Traslado a documento editable con cantidad/precio, sin guardar ni confirmar automáticamente. Precio vacío usa el snapshot actual del backend; cero explícito permanece cero. Proveedor-producto se actualiza únicamente al confirmar, respetando fecha comercial, referencia y precio neto calculado. |
| Exportación | XLSX en todas las pestañas: original editado, resumen visible, pendientes, diferencias, importables y errores, sin escrituras de negocio. |
| Integridad | Atomicidad por botón, revalidación de contexto/permisos/impuestos/versiones, duplicados compatibles agrupados para escritura sin perder filas de revisión, errores estructurados y auditoría saneada. |

Correcciones encontradas durante la aceptación:

- Separación entre el contexto del documento y el de escritura de maestros: la tarifa del documento ya no bloquea una actualización de compra.
- Primera fila de resultados parcialmente oculta por un desplazamiento incorrecto de cabecera fija.
- El estilo global de flechas invadía el campo editable: override limitado al importador, con campo 52 px y flecha 28 px.
- Foco y bordes de controles con contraste insuficiente.
- Contadores de estado que contaban productos agrupados en vez de filas originales.
- El contador de aceptadas excluía las filas con precio de compra distinto aunque estas eran importables; ahora incluye ambas condiciones.
- Pérdida del detalle abierto por recreación de objetos después de una exportación.
- Precio alternativo de documento procedente de un catálogo antiguo de pantalla.
- El primer clic en Guardar podía perderse al repetir la resolución de una Familia ya válida durante el desenfoque. Se reutiliza esa resolución únicamente en blur; la comprobación explícita con Enter permanece disponible.
- El preflight basado en ZipInputStream rechazaba un XLSX ZIP64 válido generado por SXSSFWorkbook en la exportación de edición masiva. Se usa Commons Compress, ya incluido con POI, manteniendo los límites de expansión. Su comprobación más estricta detectó además una fixture de prueba sin directorio central: se corrigió finalizando el ZIP, sin relajar el lector.
- Los booleanos nativos del XLS/XLSX, incluidos resultados booleanos almacenados de fórmulas, se normalizan a 0/1. Los textos arbitrarios como "true" no se convierten. Esto hace compatible la reimportación del XLSX nativo exportado por edición masiva sin debilitar la validación de texto.
- El almacén por defecto cargado tardíamente reseteaba el formulario abierto y cerraba Archivo; su llegada ya no borra la interacción en curso.
- Preparación de E2E antigua que seguía creando facturas de compra mediante tipos/API retirados.
- El E2E antiguo suponía que el producto estaba en la página inicial de Stock; ahora utiliza también el buscador existente cuando debe resolverlo fuera de esa página.
- Carrera del test de ControlAlertsScreen al seleccionar el filtro de la lista antes de que se abriera el detalle. Solo cambió la prueba, no ese módulo de negocio.

## Archivos modificados

Inventario acumulado de 90 archivos del plan presente en el área de trabajo, incluidas fases anteriores; no representa que todos estos archivos se hayan creado en esta última continuación. El cambio ajeno de backend-saas/docker-compose.dev.yml permanece intacto y se excluye.

- [backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/CatalogText.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/CatalogText.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/ProductBulkEditContent.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/ProductBulkEditContent.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/ProductBulkEditService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/ProductBulkEditService.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/ProductIdentifierRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/ProductIdentifierRepository.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/StoreTaxRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/StoreTaxRepository.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/SubfamilyRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/SubfamilyRepository.java>)
- [backend/src/main/java/com/tpverp/backend/catalog/WarehouseRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/WarehouseRepository.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseExcelImportMetadata.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseExcelImportMetadata.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInput.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInput.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputCommand.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputCommand.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputController.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputController.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputRepository.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputService.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputView.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputView.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseOutput.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseOutput.java>)
- [backend/src/main/java/com/tpverp/backend/party/SupplierRepository.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/party/SupplierRepository.java>)
- [backend/src/main/java/com/tpverp/backend/shared/api/ApiExceptionHandler.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/shared/api/ApiExceptionHandler.java>)
- [backend/src/main/java/com/tpverp/backend/shared/crypto/InstallationIdentityStore.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/shared/crypto/InstallationIdentityStore.java>)
- [backend/src/main/resources/application.yml](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/resources/application.yml>)
- [backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/catalog/ProductBulkEditServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/catalog/ProductBulkEditServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/inventory/WarehouseExcelImportMetadataTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/inventory/WarehouseExcelImportMetadataTest.java>)
- [backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/shared/api/ApiExceptionHandlerTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/shared/api/ApiExceptionHandlerTest.java>)
- [frontend/apps/app-gestion/src/ControlAlertsScreen.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/apps/app-gestion/src/ControlAlertsScreen.test.tsx>)
- [frontend/e2e/stock-bulk-imports.spec.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/stock-bulk-imports.spec.ts>)
- [frontend/packages/app-common/src/api/client.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/api/client.ts>)
- [frontend/packages/app-common/src/components/ErpSelect.css](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/ErpSelect.css>)
- [frontend/packages/app-common/src/components/ErpSelect.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/ErpSelect.tsx>)
- [frontend/packages/app-common/src/components/ProductCreateDialog.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/ProductCreateDialog.tsx>)
- [frontend/packages/app-common/src/components/ProductCreateDialog.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/ProductCreateDialog.test.tsx>)
- [frontend/packages/app-common/src/components/SharedExcelImportDialog.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/SharedExcelImportDialog.test.tsx>)
- [frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx>)
- [frontend/packages/app-common/src/components/StockScreen.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/StockScreen.test.tsx>)
- [frontend/packages/app-common/src/components/StockScreen.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/StockScreen.tsx>)
- [frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx>)
- [frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx>)
- [frontend/packages/app-common/src/components/excelImport.test.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/excelImport.test.ts>)
- [frontend/packages/app-common/src/components/excelImport.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/excelImport.ts>)
- [frontend/packages/app-common/src/components/stockBulkEdit.test.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/stockBulkEdit.test.ts>)
- [frontend/packages/app-common/src/components/stockBulkEdit.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/stockBulkEdit.ts>)
- [frontend/packages/app-common/src/i18n/MessagesEn.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/i18n/MessagesEn.ts>)
- [frontend/packages/app-common/src/i18n/MessagesEs.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/i18n/MessagesEs.ts>)
- [frontend/packages/app-common/src/i18n/MessagesZh.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/i18n/MessagesZh.ts>)
- [frontend/packages/app-common/src/i18n/SharedManagementMessages.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/i18n/SharedManagementMessages.ts>)
- [frontend/packages/app-common/src/styles/tpv.css](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/styles/tpv.css>)
- [backend/src/main/java/com/tpverp/backend/catalog/ProductImportConflictException.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/catalog/ProductImportConflictException.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyCommitter.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyCommitter.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyService.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyWriter.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyWriter.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportController.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportController.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPresentation.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPresentation.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPreviewService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPreviewService.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportReadService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportReadService.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportSummaryService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportSummaryService.java>)
- [backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportUploadSizeFilter.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportUploadSizeFilter.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseExcelImportProvenanceService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseExcelImportProvenanceService.java>)
- [backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputExcelAuditService.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputExcelAuditService.java>)
- [backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceImportBatchTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceImportBatchTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterCatalogPostgreSqlTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterCatalogPostgreSqlTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterPostgreSqlTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterPostgreSqlTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportControllerTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportControllerTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportOperationsTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportOperationsTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewContractMatrixTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewContractMatrixTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportReadServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportReadServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportSummaryServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportSummaryServiceTest.java>)
- [backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportUploadSizeFilterTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportUploadSizeFilterTest.java>)
- [backend/src/test/java/com/tpverp/backend/inventory/WarehouseExcelImportProvenanceServiceTest.java](<C:/Users/YLF/Documents/TPV ERP/backend/src/test/java/com/tpverp/backend/inventory/WarehouseExcelImportProvenanceServiceTest.java>)
- [docs/excel-import-family-subfamily-2026-09-07.md](<C:/Users/YLF/Documents/TPV ERP/docs/excel-import-family-subfamily-2026-09-07.md>)
- [docs/excel-import-separated-actions-2026-09-07.md](<C:/Users/YLF/Documents/TPV ERP/docs/excel-import-separated-actions-2026-09-07.md>)
- [frontend/e2e/excel-import-redesign.spec.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/excel-import-redesign.spec.ts>)
- [frontend/e2e/excel-import-volume.spec.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/excel-import-volume.spec.ts>)
- [frontend/e2e/excel-import-warehouse-api.spec.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/excel-import-warehouse-api.spec.ts>)
- [frontend/e2e/excel-import-warehouse-ui.spec.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/excel-import-warehouse-ui.spec.ts>)
- [frontend/e2e/support/ExcelImportFixture.java](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/support/ExcelImportFixture.java>)
- [frontend/e2e/support/excelImportFixture.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/e2e/support/excelImportFixture.ts>)
- [frontend/packages/app-common/src/api/productExcelImports.test.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/api/productExcelImports.test.ts>)
- [frontend/packages/app-common/src/api/productExcelImports.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/api/productExcelImports.ts>)
- [frontend/packages/app-common/src/components/ExcelImportReviewTable.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/ExcelImportReviewTable.test.tsx>)
- [frontend/packages/app-common/src/components/ExcelImportReviewTable.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/ExcelImportReviewTable.tsx>)
- [frontend/packages/app-common/src/components/SharedExcelImportManual.test.tsx](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/SharedExcelImportManual.test.tsx>)
- [frontend/packages/app-common/src/components/excelImportProductForm.ts](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/components/excelImportProductForm.ts>)
- [frontend/packages/app-common/src/styles/shared-excel-import.css](<C:/Users/YLF/Documents/TPV ERP/frontend/packages/app-common/src/styles/shared-excel-import.css>)
- [tools/Start-TpvExcelImportTestEnvironment.ps1](<C:/Users/YLF/Documents/TPV ERP/tools/Start-TpvExcelImportTestEnvironment.ps1>)
- [Este informe](<C:/Users/YLF/Documents/TPV ERP/docs/excel-import-redesign-acceptance-2026-09-07.md>)

## Decisiones técnicas

- Se reutilizan Apache POI, el catálogo, los repositorios, ProductCreateDialog, ErpSelect y TableLayoutHeaderCell. No se añaden dependencias ni migraciones.
- Los contratos de lectura, preview, apply y summary.xlsx del importador distinguen CREATE_MISSING, UPDATE_PURCHASE_PRICE, UPDATE_SELECTED_FIELDS y PREPARE_DESTINATION. El último prepara las líneas sin escribir fichas.
- Los datos originales para revisión/exportación se separan de los comandos normalizados y agrupados para escritura.
- Familia/Subfamilia continúa siendo un par de identificadores en BD. El cambio es de entrada y resolución, no una fusión del modelo.
- Las configuraciones antiguas con dos columnas de clasificación requieren reasignación visible; no se convierten silenciosamente en un borrado de subfamilia.
- El resumen solo-Excel es una preferencia de presentación, no impide las consultas internas necesarias para resolución y concurrencia.
- La guía frontend-design se aplicó respetando la estética ERP clásica. La revisión erp-code-review priorizó transacciones, permisos, concurrencia, proveedor y ausencia de efectos laterales. Playwright se usó con APIs/backend reales y BD de pruebas, sin simular endpoints del importador.
- La auditoría mantiene el comportamiento best effort del sistema: un fallo posterior de auditoría no se presenta falsamente como rollback de una escritura ya confirmada.

## Validaciones realizadas

### Backend

- Suite completa: 3.398 casos, 0 fallos, 0 errores, 75 omitidos por las condiciones de integración; BUILD SUCCESS. Evidencia: backend/target/excel-import-backend-full-validation.log.
- Suite focal de importación: 219/219 correctas; evidencia: backend/target/excel-import-focused-validation.log.
- Últimos ajustes ZIP64/booleanos: lector, preview, resumen y operaciones vuelven a pasar 123/123, sin omisiones. Evidencia: backend/target/excel-import-zip64-validation.log. Incluye XLSX streaming con dos hojas, selección exclusiva de la primera y booleanos nativos/caché en XLS y XLSX.
- Escritor con CatalogService/repositorios reales y PostgreSQL: 5/5 correctas, más 8/8 operaciones. Se fuerza fallo en la segunda alta/actualización y se comprueba rollback de productos, identificadores, precios y versiones; también éxito y versión obsoleta. Evidencia: backend/target/excel-import-write-validation.log.
- Proveedor PostgreSQL: 3/3 correctas, incluidas fechas anterior/posterior, conservación de referencia y upserts concurrentes. Constan en backend/target/excel-import-integrity-validation.log. Esa ejecución encontró un error en el trigger del fixture de actualización; se corrigió y el escritor completo volvió a pasar 5/5 en el log anterior. No se presenta esa ejecución intermedia como suite global correcta.
- Casos cubiertos: firma falsa, cifrado/corrupción/macros, expansión ZIP, límites, errores nativos y fórmulas sin caché, fechas 1900/1904/bisiestos/años cortos, códigos largos, AA+, identidades ambiguas, campos especiales, impuestos, ceros, duplicados, permisos combinados y proveedor ajeno/inactivo.
- Las integraciones específicas y E2E usan PostgreSQL de pruebas en 127.0.0.1:55439/tpv_excel_plan_test. La suite global también utiliza los esquemas aleatorios de la base de pruebas habitual tpv_erp_test para pruebas incondicionales. No se han escrito tablas de negocio.

### Frontend

- Suite completa: 214 archivos y 2.001 pruebas correctas. Evidencia: output/excel-import-frontend-complete.log.
- Suite focal posterior: 10 archivos y 241/241 pruebas correctas, incluidos Stock, Almacén, altas manuales, tablas, API, normalizadores y guardia ES/EN/ZH. Evidencia: output/excel-import-frontend-final.log. El ajuste posterior del formulario de productos pasa además sus 43/43 pruebas; no se suman pruebas solapadas a la cifra global.
- Regresión final de los tres componentes modificados al cerrar: 170/170 correctas (importador 81, formulario de productos 43, documento de Almacén 46). Incluye el contador con precio distinto y la carga tardía de almacén, conservando el refresco de un nuevo snapshot del mismo documento. Evidencia: output/excel-import-final-regressions.log.
- Tipos TypeScript, builds APP VENTA/APP GESTIÓN y presupuesto del bundle correctos tras los últimos ajustes del importador y Almacén. Evidencia: output/excel-import-build-final.log. JS VENTA: 768.872/800.000 bytes; CSS VENTA: 450.119/460.000; JS GESTIÓN: 389.177/600.000; CSS GESTIÓN: 450.119/520.000.
- Contraste calculado sobre los colores efectivos: texto 17,09:1; cabecera 10,64:1; acción 5,32:1; ayuda de fechas 5,59:1; borde de campo 3,90:1.
- E2E de 5.000 filas POI/columnas AA: revisión conserva 5.000 originales, agrupa un comando compatible ignorando cantidades distintas en Stock y renderiza menos de 50 filas DOM. Scroll con rueda hasta fila 5.001 y horizontal; catálogo sin cambios.
- Se conservan capturas de revisión a 1366/1920 px, configuración, volumen y borrador de Almacén bajo output/playwright/.

### Navegador y cierre

Ronda conjunta final: **10/10 correctas**, sin reintentos configurados, 1,2 minutos. Evidencia: output/excel-import-e2e-final.log.

- XLSX: los cuatro controles editables mediante teclado y ratón, campos de 52 px, resize, altas automáticas confirmadas, compra exclusivamente y atributos marcados.
- Resumen con casilla true y false: comparación de la matriz completa exportada con las cabeceras y celdas visibles; otras pestañas conservan su modalidad independientemente de esa casilla.
- Abrir/aplicar/exportar: ninguna llamada a escritura de maestros y snapshot del producto inalterado.
- Alta manual: código cambiado en el formulario, alta real verificada por API, apertura del siguiente pendiente, cancelación y conservación del código original de la fila Excel.
- Traslado a Stock de XLSX y XLS POI reales, sin Cantidad ni modificación de fichas.
- Volumen: 5.000 filas originales y columna AA, sin uso de cantidades en Stock y con ventana de menos de 50 filas DOM.
- Almacén API: preparación sin escritura, firma de procedencia, proveedor pendiente hasta confirmar, referencia/precio/descuento/neto/fecha comercial, doble confirmación, acceso sin sesión y rechazo de versión obsoleta al repetir actualización de compra.
- Almacén UI: producto y stock sin cambios al importar, líneas correctas y guardado explícito BORRADOR sin confirmación automática.
- Edición masiva: exportación y reimportación del mismo fichero XLSX real, sin reemplazar la fixture ni editar sus valores; importaciones de proveedor, factura, albarán y familia siguen funcionando.

Se revisaron las capturas finales y git diff --check no detectó errores de formato. El cambio ajeno backend-saas/docker-compose.dev.yml permanece preservado.

Se detuvieron únicamente el backend/Vite de esta aceptación y el contenedor tpv-excel-plan-test-20260907. Su BD temporal se eliminó por auto-remove: son fixtures regenerables mediante el script y los E2E. Las evidencias y capturas se conservan. Los procesos habituales del usuario permanecieron en ejecución.

## Riesgos detectados

- APP VENTA conserva avisos de proximidad al presupuesto de JS/CSS, sin superar el límite.
- El formato Excel impone longitud máxima por celda; los errores extensos se continúan en filas de exportación, sin truncar silenciosamente su contenido.
- Además de 10 MB/5.000 filas detectadas/256 columnas/250.000 celdas no vacías, el lector tiene límites defensivos de materialización, texto, formatos y expansión ZIP. No se acepta un libro arbitrariamente grande por tener pocas celdas con texto.
- No se certifica despliegue ni comportamiento de una instalación de producción mediante estas pruebas. Los datos utilizados son de pruebas.
- No es una modificación fiscal ni una validación AEAT/VeriFactu. El entorno de pruebas desactiva los envíos fiscales.

## Trabajo pendiente

No quedan tareas de implementación ni validaciones específicas del plan conocidas pendientes. Las 75 omisiones de la suite global corresponden a condiciones de integración de esa suite y se explicitan arriba; no se contabilizan como pruebas ejecutadas.

La publicación, instalación y comprobación en producción quedan fuera de esta aceptación local y no se han realizado.

## Próximos pasos recomendados

Revisar el diff y este informe antes de autorizar publicación. No se ha realizado commit, push, despliegue ni modificación de la base de negocio.
