# Publicación del importador Excel y precios de tres decimales

Fecha: 8 de septiembre de 2026. Esquema del backend ERP: V241.

## Trabajo realizado

- Lector XLS/XLSX en backend, validación de firma, límites y fórmulas almacenadas, errores por celda y clasificación por identidad.
- Acciones separadas para revisar, crear productos, actualizar atributos marcados y preparar el destino. Operaciones de escritura con comprobaciones de permisos, versiones, atomicidad y auditoría.
- Exclusión de Cantidad en Stock; conservación de cantidades y datos propios de línea en documentos de Almacén. Procedencia firmada de Excel y actualización del proveedor únicamente al confirmar.
- Interfaz ERP compacta ES/EN/ZH: asignación por columna o valor global, valores predeterminados, familia/subfamilia unificada, fechas, tablas con cabecera fija, selección, scroll y redimensionado.
- Revisión y exportación XLSX con diferencias actuales/nuevas, atributos vacíos ocultos en pantalla, códigos/cantidad sin comparación duplicada, segundo código de barras al final y anchuras según contenido.
- Persistencia de precios unitarios con tres decimales, redondeo monetario después de multiplicar y adaptación de formularios, autorizaciones e impresión.
- Correcciones del guardado de borradores: reemplazo ordenado de líneas, conservación del catálogo de productos recién importados, recuperación de identidades ausentes en la caché y cálculo decimal de importes en pantalla.

## Archivos modificados

Inventario de 145 archivos de implementación, pruebas, plantillas y herramientas; este documento es el archivo adicional de publicación.

<details>
<summary>Inventario completo</summary>

- `backend/pom.xml`
- `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`
- `backend/src/main/java/com/tpverp/backend/catalog/CatalogText.java`
- `backend/src/main/java/com/tpverp/backend/catalog/Product.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductBulkEditContent.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductBulkEditService.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductIdentifierRepository.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductImportConflictException.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductPrice.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductPriceHistory.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductPriceRuleService.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplier.java`
- `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java`
- `backend/src/main/java/com/tpverp/backend/catalog/StoreTaxRepository.java`
- `backend/src/main/java/com/tpverp/backend/catalog/SubfamilyRepository.java`
- `backend/src/main/java/com/tpverp/backend/catalog/WarehouseRepository.java`
- `backend/src/main/java/com/tpverp/backend/document/DocumentLine.java`
- `backend/src/main/java/com/tpverp/backend/document/Money.java`
- `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`
- `backend/src/main/java/com/tpverp/backend/document/PreviousTicketImportService.java`
- `backend/src/main/java/com/tpverp/backend/document/SaleDocumentMutationAuthorizationService.java`
- `backend/src/main/java/com/tpverp/backend/document/SaleLineDeletionService.java`
- `backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionService.java`
- `backend/src/main/java/com/tpverp/backend/document/SalesInvoiceRectificationService.java`
- `backend/src/main/java/com/tpverp/backend/document/TemporaryPriceAuthorizationGrant.java`
- `backend/src/main/java/com/tpverp/backend/document/TemporaryPriceAuthorizationService.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyCommitter.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyService.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportApplyWriter.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportController.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPresentation.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportPreviewService.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportReadService.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportSummaryService.java`
- `backend/src/main/java/com/tpverp/backend/excel/ProductExcelImportUploadSizeFilter.java`
- `backend/src/main/java/com/tpverp/backend/excel/StockExcelExportService.java`
- `backend/src/main/java/com/tpverp/backend/excel/WarehouseDocumentExcelExportService.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseExcelImportMetadata.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseExcelImportProvenanceService.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInput.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputCommand.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputController.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputExcelAuditService.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputLine.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputLineCommand.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputRepository.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputService.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputView.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseOutput.java`
- `backend/src/main/java/com/tpverp/backend/inventory/WarehouseOutputLine.java`
- `backend/src/main/java/com/tpverp/backend/party/SupplierRepository.java`
- `backend/src/main/java/com/tpverp/backend/promotion/AuthoritativePromotionPricing.java`
- `backend/src/main/java/com/tpverp/backend/promotion/ProductLabelCommercialContextService.java`
- `backend/src/main/java/com/tpverp/backend/shared/api/ApiExceptionHandler.java`
- `backend/src/main/java/com/tpverp/backend/shared/crypto/InstallationIdentityStore.java`
- `backend/src/main/resources/db/migration/V241__unit_prices_three_decimals.sql`
- `backend/src/main/resources/META-INF/tpv-erp-release.properties`
- `backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceImportBatchTest.java`
- `backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/catalog/ProductBulkEditServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/document/DocumentLineTotalsTest.java`
- `backend/src/test/java/com/tpverp/backend/document/DocumentRulesTest.java`
- `backend/src/test/java/com/tpverp/backend/document/MoneyTest.java`
- `backend/src/test/java/com/tpverp/backend/document/PosCashServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/document/PreviousTicketImportRepositoryPostgreSqlTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterCatalogPostgreSqlTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterPostgreSqlTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportApplyWriterTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportControllerTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportOperationsTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewContractMatrixTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportPreviewServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportReadServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportSummaryServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/ProductExcelImportUploadSizeFilterTest.java`
- `backend/src/test/java/com/tpverp/backend/excel/WarehouseDocumentExcelExportServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/inventory/WarehouseExcelImportMetadataTest.java`
- `backend/src/test/java/com/tpverp/backend/inventory/WarehouseExcelImportProvenanceServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputDraftPostgreSqlTest.java`
- `backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputServiceTest.java`
- `backend/src/test/java/com/tpverp/backend/persistence/FiscalReleaseBuildProfileContractTest.java`
- `backend/src/test/java/com/tpverp/backend/promotion/AuthoritativePromotionPricingTest.java`
- `backend/src/test/java/com/tpverp/backend/shared/api/ApiExceptionHandlerTest.java`
- `frontend/apps/app-gestion/src/ControlAlertsScreen.test.tsx`
- `frontend/desktop/a4-renderer.cjs`
- `frontend/desktop/product-label-renderer.cjs`
- `frontend/desktop/ticket-renderer.cjs`
- `frontend/desktop/ticket-renderer.test.mjs`
- `frontend/e2e/excel-import-redesign.spec.ts`
- `frontend/e2e/excel-import-volume.spec.ts`
- `frontend/e2e/excel-import-warehouse-api.spec.ts`
- `frontend/e2e/excel-import-warehouse-ui.spec.ts`
- `frontend/e2e/stock-bulk-imports.spec.ts`
- `frontend/e2e/support/ExcelImportFixture.java`
- `frontend/e2e/support/excelImportFixture.ts`
- `frontend/packages/app-common/src/api/client.ts`
- `frontend/packages/app-common/src/api/productExcelImports.test.ts`
- `frontend/packages/app-common/src/api/productExcelImports.ts`
- `frontend/packages/app-common/src/components/ErpSelect.css`
- `frontend/packages/app-common/src/components/ErpSelect.tsx`
- `frontend/packages/app-common/src/components/excelImport.test.ts`
- `frontend/packages/app-common/src/components/excelImport.ts`
- `frontend/packages/app-common/src/components/excelImportProductForm.ts`
- `frontend/packages/app-common/src/components/ExcelImportReviewTable.test.tsx`
- `frontend/packages/app-common/src/components/ExcelImportReviewTable.tsx`
- `frontend/packages/app-common/src/components/ProductCreateDialog.test.tsx`
- `frontend/packages/app-common/src/components/ProductCreateDialog.tsx`
- `frontend/packages/app-common/src/components/SaleOpenPriceDialog.test.tsx`
- `frontend/packages/app-common/src/components/SaleOpenPriceDialog.tsx`
- `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- `frontend/packages/app-common/src/components/SaleScreen.tsx`
- `frontend/packages/app-common/src/components/SalesDocumentScreen.tsx`
- `frontend/packages/app-common/src/components/SalesInvoiceRectificationDialog.tsx`
- `frontend/packages/app-common/src/components/SalesReportScreen.tsx`
- `frontend/packages/app-common/src/components/SharedExcelImportDialog.test.tsx`
- `frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx`
- `frontend/packages/app-common/src/components/SharedExcelImportManual.test.tsx`
- `frontend/packages/app-common/src/components/stockBulkEdit.test.ts`
- `frontend/packages/app-common/src/components/stockBulkEdit.ts`
- `frontend/packages/app-common/src/components/StockProductInformationPanel.tsx`
- `frontend/packages/app-common/src/components/StockSalesHistoryPanel.tsx`
- `frontend/packages/app-common/src/components/StockScreen.test.tsx`
- `frontend/packages/app-common/src/components/StockScreen.tsx`
- `frontend/packages/app-common/src/components/useExcelTableHeader.ts`
- `frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx`
- `frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx`
- `frontend/packages/app-common/src/components/WarehouseDocumentReload.test.tsx`
- `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- `frontend/packages/app-common/src/i18n/SharedManagementMessages.ts`
- `frontend/packages/app-common/src/money.test.ts`
- `frontend/packages/app-common/src/money.ts`
- `frontend/packages/app-common/src/styles/shared-excel-import.css`
- `frontend/packages/app-common/src/styles/tpv.css`
- `frontend/packages/app-common/src/warehouse/warehouseDocumentPrinting.ts`
- `plantillas documentos/ALBARAN_VENTA_A4.jrxml`
- `plantillas documentos/FACTURA_VENTA_A4.jrxml`
- `plantillas documentos/FACTURA_VENTA_TICKET_80.jrxml`
- `plantillas documentos/subreport/ticket_contenido_compacta.jrxml`
- `plantillas documentos/subreport/ticket_contenido_minimalista.jrxml`
- `plantillas documentos/subreport/ticket_contenido.jrxml`
- `plantillas documentos/ticket_anulado.jrxml`
- `tools/Start-TpvExcelImportTestEnvironment.ps1`

</details>

No se publican capturas de herramientas, informes operativos con datos reales, SQL de reparación de una factura concreta ni la configuración local de SaaS. Permanecen conservados localmente.

## Decisiones técnicas

- Se sincronizó la base con el commit remoto de SaaS mediante fast-forward; no se mezclan aquí actualizaciones de dependencias pendientes.
- Se conservó una copia verificada de todos los archivos locales y una instantánea Git recuperable antes de sincronizar.
- La revisión ERP orientó la conservación de cambios ajenos, el staging explícito, la revisión de migraciones y la exclusión de datos operativos del repositorio público.
- V241 amplía nueve columnas de precio a `numeric(20,3)`, manteniendo los 17 dígitos enteros previos. No recalcula importes, impuestos ni pagos históricos.
- Los totales monetarios siguen a dos decimales. El cálculo de Almacén conserva el orden del backend: precio por cantidad, redondeo de subtotal de línea, descuento de línea, suma y descuento del documento.
- Se reutilizan Apache POI, las APIs y las estructuras existentes; no se añade una biblioteca JavaScript de cálculo decimal.
- La preparación de esta publicación solo corrigió dos líneas vacías sobrantes al final de archivos nuevos; no añadió cambios funcionales a la implementación revisada.

## Validaciones realizadas

- Backend: 439 pruebas focalizadas, cero fallos, cero errores y cero omitidas en esta batería. Ejecutadas con el wrapper Maven del proyecto en una copia aislada del código, sin sustituir clases de los servicios abiertos.
- Frontend completo: 2.092 pruebas en 215 archivos; 2.091 correctas y un fallo intermitente en `SafeRetirementDialog.test.tsx`. La repetición aislada pasó sus cinco pruebas sin modificar el código ni la prueba.
- El fallo intermitente comprobaba el contenido del estado cuando todavía mostraba carga; no correspondía a una operación fallida de importación.
- Renderizado Electron de tickets: ocho pruebas correctas mediante Vitest. Sintaxis de los tres renderizadores modificados comprobada con Node.
- Compilaciones TypeScript/Vite de APP VENTA y APP GESTIÓN correctas.
- Presupuesto de bundle correcto; VENTA mantiene avisos de proximidad a sus límites.
- Revisión del diff preparado: sin claves privadas, tokens reconocibles ni identificadores de las incidencias reales en las líneas añadidas; `git diff --cached --check` correcto.
- Flyway ERP: 237 migraciones versionadas, máxima V241; historial local, fuentes y recursos compilados coherentes, sin duplicados ni diferencias de checksum.
- Flyway SaaS: código actualizado hasta V52, BD local todavía en V48. V49–V52 no se aplicaron durante esta publicación.
- No se ejecutaron migraciones, reparaciones de datos, reinicios, confirmaciones de documentos ni pruebas E2E contra la BD habitual.

## Riesgos detectados

- La suite frontend conserva un caso de sincronización intermitente fuera del importador; no se presenta la ejecución completa como totalmente verde.
- V241 requiere copia de seguridad y una ventana coordinada en instalaciones aún pendientes de actualizar. Reducir otra vez la escala de precios podría perder precisión.
- SaaS tiene migraciones pendientes; publicar o traer su código no equivale a haber actualizado su BD.
- Esta publicación no certifica una instalación fiscal de producción ni sustituye las comprobaciones de despliegue.
- Las validaciones E2E y PostgreSQL de fases anteriores no se repitieron en esta operación de Git. Se conserva su evidencia local; no se publican sus datos operativos.

## Trabajo pendiente

- Revisar los resultados de CI que se generen para el commit publicado.
- Planificar por separado la actualización del entorno SaaS, con copia de seguridad y validación de V49–V52.
- Si reaparece el fallo intermitente de retirada de productos, revisar la espera de la prueba sin alterar las reglas de negocio.

## Próximos pasos recomendados

Revisar CI y mantener las actualizaciones de dependencias como trabajo independiente. No reiniciar SaaS ni aplicar sus migraciones como efecto implícito de este commit.
