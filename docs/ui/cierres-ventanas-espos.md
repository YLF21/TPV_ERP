# Cierres de ventanas de esPOS

Inventario de los usos actuales de `WindowCloseButton` en el worktree revisado. El recuento es de ubicaciones JSX, no de ventanas simultáneas: un componente reutilizable puede producir distintas ventanas según su título o sus props. Los enlaces de este documento usan rutas del repositorio y líneas observadas en el código al preparar el inventario.

## Cierres inferiores y colores vigentes — 06/10/2026

En APP VENTA y APP GESTIÓN se retiran los botones inferiores Cerrar/Cancelar de las ventanas normales del inventario. La cruz de cabecera permanece, con su callback y bloqueo existentes. Los avisos, confirmaciones y resultados de operaciones conservan su salida inferior. Aceptar, Insertar, Guardar, Aplicar, Seleccionar y acciones equivalentes usan #07254F; los botones negativos conservan #B42318 y el radio de 3 px.

El componente compartido [DialogDismissButton.tsx](../../frontend/packages/app-common/src/components/DialogDismissButton.tsx) no renderiza ningún botón en escritorio para una salida normal: tampoco queda un control oculto en la navegación por Tab. `notice` mantiene el botón cuando el contenido es un aviso. Las compilaciones PDA conservan sus botones anteriores. Los controles del teclado, calendario, navegación principal y cancelación interna de una operación no se incluyen en esta retirada.

Se revisaron 61 componentes con este helper: 86 ubicaciones normales, 3 ubicaciones dependientes del estado y 2 salidas de aviso expresamente conservadas. Son ubicaciones JSX, no un recuento de ventanas visibles simultáneamente.

Excepciones comprobadas: confirmación de eliminación de series sobrantes; apertura de caja exigida antes de vender; resultado de retirada/rectificación registrada; catálogo de alertas agotado; confirmaciones de borrado, cambios pendientes, apagado y recuentos; revisión de un cobro incierto o impresión fallida. La apertura embebida de caja y los formularios de autorización/referencia sin cruz conservan su salida existente. El editor de línea de almacén utiliza el handler de cierre existente también desde la X para restaurar el foco.

### Componentes revisados de cierres inferiores

| Componente | Ubicaciones | Excepción del helper |
|---|---:|---|
| [CashDenominationDialog.tsx](../../frontend/packages/app-common/src/components/CashDenominationDialog.tsx) | 1 | Ventana normal |
| [CashPaymentDialog.tsx](../../frontend/packages/app-common/src/components/CashPaymentDialog.tsx) | 1 | Ventana normal |
| [CustomerDocumentsDialog.tsx](../../frontend/packages/app-common/src/components/CustomerDocumentsDialog.tsx) | 1 | Ventana normal |
| [CustomerModel347Dialog.tsx](../../frontend/packages/app-common/src/components/CustomerModel347Dialog.tsx) | 1 | Ventana normal |
| [CustomerPendingSaleDialog.tsx](../../frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx) | 2 | Ventana normal |
| [CustomerReceivablesScreen.tsx](../../frontend/packages/app-common/src/components/CustomerReceivablesScreen.tsx) | 2 | Ventana normal |
| [GiftReceiptDialog.tsx](../../frontend/packages/app-common/src/components/GiftReceiptDialog.tsx) | 1 | Ventana normal |
| [ManualCardReferenceDialog.tsx](../../frontend/packages/app-common/src/components/ManualCardReferenceDialog.tsx) | 1 | Ventana normal |
| [MemberWalletDialog.tsx](../../frontend/packages/app-common/src/components/MemberWalletDialog.tsx) | 1 | Ventana normal |
| [ParkedSalesDialog.tsx](../../frontend/packages/app-common/src/components/ParkedSalesDialog.tsx) | 1 | Ventana normal |
| [PartyDirectoryPanel.tsx](../../frontend/packages/app-common/src/components/PartyDirectoryPanel.tsx) | 3 | Ventana normal |
| [PaymentAllocationPanel.tsx](../../frontend/packages/app-common/src/components/PaymentAllocationPanel.tsx) | 1 | Ventana normal |
| [ProductCommercialHistory.tsx](../../frontend/packages/app-common/src/components/ProductCommercialHistory.tsx) | 1 | Ventana normal |
| [ProductCreateDialog.tsx](../../frontend/packages/app-common/src/components/ProductCreateDialog.tsx) | 1 | Ventana normal |
| [SafeRetirementDialog.tsx](../../frontend/packages/app-common/src/components/SafeRetirementDialog.tsx) | 1 | Ventana normal |
| [SaleCalculatorDialog.tsx](../../frontend/packages/app-common/src/components/SaleCalculatorDialog.tsx) | 1 | Ventana normal |
| [SaleCashDrawerAuthorizationDialog.tsx](../../frontend/packages/app-common/src/components/SaleCashDrawerAuthorizationDialog.tsx) | 1 | Ventana normal |
| [SaleCashSessionDialog.tsx](../../frontend/packages/app-common/src/components/SaleCashSessionDialog.tsx) | 2 | Según estado |
| [SaleCashWithdrawalDialog.tsx](../../frontend/packages/app-common/src/components/SaleCashWithdrawalDialog.tsx) | 2 | Aviso |
| [SaleCustomerCreateDialog.tsx](../../frontend/packages/app-common/src/components/SaleCustomerCreateDialog.tsx) | 1 | Ventana normal |
| [SaleCustomerReceivablesDialog.tsx](../../frontend/packages/app-common/src/components/SaleCustomerReceivablesDialog.tsx) | 1 | Ventana normal |
| [SaleInternalEanDialog.tsx](../../frontend/packages/app-common/src/components/SaleInternalEanDialog.tsx) | 1 | Ventana normal |
| [SaleInvoiceCustomerListDialog.tsx](../../frontend/packages/app-common/src/components/SaleInvoiceCustomerListDialog.tsx) | 1 | Ventana normal |
| [SaleMutationAuthorizationDialog.tsx](../../frontend/packages/app-common/src/components/SaleMutationAuthorizationDialog.tsx) | 1 | Ventana normal |
| [SaleOpenPriceDialog.tsx](../../frontend/packages/app-common/src/components/SaleOpenPriceDialog.tsx) | 1 | Ventana normal |
| [SalePaymentCheckout.tsx](../../frontend/packages/app-common/src/components/SalePaymentCheckout.tsx) | 1 | Ventana normal |
| [SaleProductConsultationDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductConsultationDialog.tsx) | 1 | Ventana normal |
| [SaleProductInformationDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductInformationDialog.tsx) | 1 | Ventana normal |
| [SaleProductLabelDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductLabelDialog.tsx) | 2 | Ventana normal |
| [SaleProductSalesHistoryDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductSalesHistoryDialog.tsx) | 1 | Ventana normal |
| [SaleScreen.tsx](../../frontend/packages/app-common/src/components/SaleScreen.tsx) | 8 | Ventana normal |
| [SalesDocumentCustomerDialog.tsx](../../frontend/packages/app-common/src/components/SalesDocumentCustomerDialog.tsx) | 1 | Ventana normal |
| [SalesDocumentDraftDialog.tsx](../../frontend/packages/app-common/src/components/SalesDocumentDraftDialog.tsx) | 1 | Ventana normal |
| [SalesDocumentScreen.tsx](../../frontend/packages/app-common/src/components/SalesDocumentScreen.tsx) | 1 | Ventana normal |
| [SaleSerialNumberDialog.tsx](../../frontend/packages/app-common/src/components/SaleSerialNumberDialog.tsx) | 1 | Según estado |
| [SalesInvoiceRectificationDialog.tsx](../../frontend/packages/app-common/src/components/SalesInvoiceRectificationDialog.tsx) | 1 | Según estado |
| [SalesReportScreen.tsx](../../frontend/packages/app-common/src/components/SalesReportScreen.tsx) | 2 | Ventana normal |
| [SaleTicketCancellationDialog.tsx](../../frontend/packages/app-common/src/components/SaleTicketCancellationDialog.tsx) | 1 | Ventana normal |
| [SaleTouchControls.tsx](../../frontend/packages/app-common/src/components/SaleTouchControls.tsx) | 1 | Ventana normal |
| [StockBulkDecimalDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkDecimalDialog.tsx) | 1 | Ventana normal |
| [StockBulkFamilyDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkFamilyDialog.tsx) | 1 | Ventana normal |
| [StockBulkFilterDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkFilterDialog.tsx) | 1 | Ventana normal |
| [StockPermissionsDialog.tsx](../../frontend/packages/app-common/src/components/StockPermissionsDialog.tsx) | 1 | Ventana normal |
| [StockScreen.tsx](../../frontend/packages/app-common/src/components/StockScreen.tsx) | 8 | Ventana normal |
| [StockSettingsDialog.tsx](../../frontend/packages/app-common/src/components/StockSettingsDialog.tsx) | 1 | Ventana normal |
| [TicketManagementDialog.tsx](../../frontend/packages/app-common/src/components/TicketManagementDialog.tsx) | 1 | Ventana normal |
| [TicketReturnDialog.tsx](../../frontend/packages/app-common/src/components/TicketReturnDialog.tsx) | 1 | Ventana normal |
| [WarehouseDocumentDialog.tsx](../../frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx) | 2 | Ventana normal |
| [WarehouseSupplierDialog.tsx](../../frontend/packages/app-common/src/components/WarehouseSupplierDialog.tsx) | 2 | Ventana normal |
| [ControlAlertsScreen.tsx](../../frontend/apps/app-gestion/src/ControlAlertsScreen.tsx) | 2 | Aviso |
| [FamiliesScreen.tsx](../../frontend/apps/app-gestion/src/FamiliesScreen.tsx) | 2 | Ventana normal |
| [FiscalComplianceView.tsx](../../frontend/apps/app-gestion/src/FiscalComplianceView.tsx) | 1 | Ventana normal |
| [FiscalRecordsView.tsx](../../frontend/apps/app-gestion/src/FiscalRecordsView.tsx) | 2 | Ventana normal |
| [GestionGroupUnlockDialog.tsx](../../frontend/apps/app-gestion/src/GestionGroupUnlockDialog.tsx) | 1 | Ventana normal |
| [MemberCategoriesScreen.tsx](../../frontend/apps/app-gestion/src/MemberCategoriesScreen.tsx) | 1 | Ventana normal |
| [SecurityAdministrationScreen.tsx](../../frontend/apps/app-gestion/src/SecurityAdministrationScreen.tsx) | 3 | Ventana normal |
| [StockCountDocumentWindow.tsx](../../frontend/apps/app-gestion/src/StockCountDocumentWindow.tsx) | 1 | Ventana normal |
| [SupplierManagementScreen.tsx](../../frontend/apps/app-gestion/src/SupplierManagementScreen.tsx) | 1 | Ventana normal |
| [VerifactuCertificateView.tsx](../../frontend/apps/app-gestion/src/VerifactuCertificateView.tsx) | 1 | Ventana normal |
| [WarehouseManagementScreen.tsx](../../frontend/apps/app-gestion/src/WarehouseManagementScreen.tsx) | 2 | Ventana normal |
| [WarehouseOperationsScreen.tsx](../../frontend/apps/app-gestion/src/WarehouseOperationsScreen.tsx) | 1 | Ventana normal |

## Patrón compartido

En escritorio, `WindowCloseButton` representa una cruz blanca de 22 px y trazo grueso, centrada en un botón de 44 × 44 px. El botón queda transparente y sin borde en estado normal; se coloca a 3 px de los bordes superior y derecho. El recuadro aparece al pasar el puntero o al recibir foco visible, con radio de 4 px. En cabeceras claras conserva el fondo y cambia la cruz a azul oscuro, usando una capa azul tenue en hover/foco para mantener contraste. Las cabeceras que alojan el control reservan espacio para que no se superponga al título. En las ramas PDA el componente conserva su renderizado propio; varios call sites muestran además texto “Cerrar” en PDA.

## esPOS VENTA

Los componentes de venta residen en `frontend/packages/app-common/src/components`; `frontend/apps/app-venta/src` no tiene un uso JSX directo de `WindowCloseButton` en el código revisado.

### Diálogos de caja, cliente y operación

| Ventana visible | Ubicación |
|---|---|
| Pago en efectivo | [CashPaymentDialog.tsx:111](../../frontend/packages/app-common/src/components/CashPaymentDialog.tsx) |
| Consulta/gestión de cobros pendientes, ventana principal e historial de detalle | [CustomerReceivablesScreen.tsx:322](../../frontend/packages/app-common/src/components/CustomerReceivablesScreen.tsx), [CustomerReceivablesScreen.tsx:393](../../frontend/packages/app-common/src/components/CustomerReceivablesScreen.tsx) |
| Autorización de pago de documento de venta y ventana de venta pendiente | [CustomerPendingSaleDialog.tsx:1113](../../frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx), [CustomerPendingSaleDialog.tsx:1237](../../frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx). La segunda cabecera usa `title` del llamador o el título traducido de venta pendiente. |
| Emisión de ticket regalo | [GiftReceiptDialog.tsx:415](../../frontend/packages/app-common/src/components/GiftReceiptDialog.tsx) |
| Monedero del socio | [MemberWalletDialog.tsx:287](../../frontend/packages/app-common/src/components/MemberWalletDialog.tsx) |
| Ventas aparcadas | [ParkedSalesDialog.tsx:289](../../frontend/packages/app-common/src/components/ParkedSalesDialog.tsx) |
| Asignación de pagos/cobros del documento, título y texto accesible según el contexto | [PaymentAllocationPanel.tsx:905](../../frontend/packages/app-common/src/components/PaymentAllocationPanel.tsx) |
| Autorización de apertura/cierre de cajón | [SaleCashDrawerAuthorizationDialog.tsx:70](../../frontend/packages/app-common/src/components/SaleCashDrawerAuthorizationDialog.tsx) |
| Sesión de caja (solo cuando no está embebida) | [SaleCashSessionDialog.tsx:365](../../frontend/packages/app-common/src/components/SaleCashSessionDialog.tsx) |
| Consulta de recibos/cobros del cliente | [SaleCustomerReceivablesDialog.tsx:151](../../frontend/packages/app-common/src/components/SaleCustomerReceivablesDialog.tsx) |
| Alta de cliente desde venta | [SaleCustomerCreateDialog.tsx:138](../../frontend/packages/app-common/src/components/SaleCustomerCreateDialog.tsx) |
| Autorización de modificación de venta | [SaleMutationAuthorizationDialog.tsx:156](../../frontend/packages/app-common/src/components/SaleMutationAuthorizationDialog.tsx) |
| Calculadora de venta | [SaleCalculatorDialog.tsx:456](../../frontend/packages/app-common/src/components/SaleCalculatorDialog.tsx) |

### Venta: consulta, selección, tickets y documentos

| Ventana visible | Ubicación |
|---|---|
| Consulta de precio | [SalePriceConsultationDialog.tsx:154](../../frontend/packages/app-common/src/components/SalePriceConsultationDialog.tsx) |
| Consulta de producto | [SaleProductConsultationDialog.tsx:107](../../frontend/packages/app-common/src/components/SaleProductConsultationDialog.tsx) |
| Información del producto desde venta | [SaleProductInformationDialog.tsx:334](../../frontend/packages/app-common/src/components/SaleProductInformationDialog.tsx) |
| Historial de ventas del producto | [SaleProductSalesHistoryDialog.tsx:100](../../frontend/packages/app-common/src/components/SaleProductSalesHistoryDialog.tsx) |
| Búsqueda/selección de producto | [SaleProductSearchDialog.tsx:244](../../frontend/packages/app-common/src/components/SaleProductSearchDialog.tsx) |
| Selección de número de serie/lote | [SaleSerialNumberDialog.tsx:82](../../frontend/packages/app-common/src/components/SaleSerialNumberDialog.tsx) |
| Borrador de documento de venta | [SalesDocumentDraftDialog.tsx:188](../../frontend/packages/app-common/src/components/SalesDocumentDraftDialog.tsx) |
| Selección de cliente para documento de venta | [SalesDocumentCustomerDialog.tsx:99](../../frontend/packages/app-common/src/components/SalesDocumentCustomerDialog.tsx) |
| Rectificación de factura | [SalesInvoiceRectificationDialog.tsx:339](../../frontend/packages/app-common/src/components/SalesInvoiceRectificationDialog.tsx) |
| Cancelación de ticket | [SaleTicketCancellationDialog.tsx:437](../../frontend/packages/app-common/src/components/SaleTicketCancellationDialog.tsx) |
| Factura de ticket | [SaleTicketInvoiceDialog.tsx:325](../../frontend/packages/app-common/src/components/SaleTicketInvoiceDialog.tsx) |
| Gestión de tickets | [TicketManagementDialog.tsx:332](../../frontend/packages/app-common/src/components/TicketManagementDialog.tsx) |
| Devolución de ticket | [TicketReturnDialog.tsx:405](../../frontend/packages/app-common/src/components/TicketReturnDialog.tsx) |
| Informe de ventas: actividad, vista previa de documento y filtros | [SalesReportScreen.tsx:1917](../../frontend/packages/app-common/src/components/SalesReportScreen.tsx), [SalesReportScreen.tsx:4243](../../frontend/packages/app-common/src/components/SalesReportScreen.tsx), [SalesReportScreen.tsx:4420](../../frontend/packages/app-common/src/components/SalesReportScreen.tsx) |

### Plantilla SaleActionDialog: 13 ventanas del TPV

Una única X de plantilla en [SaleScreen.tsx:7190](../../frontend/packages/app-common/src/components/SaleScreen.tsx) cubre estas instancias: comentario interno de venta, eliminar venta actual, eliminar todos los artículos, salida de impresión, descuento de documento, precio temporal, cantidad, descuento de línea, nombre temporal del artículo, precio temporal del artículo, seleccionar cliente, anular línea y producto desactivado. Los nombres salen de las claves de título de cada llamada; algunas opciones se presentan solo cuando la condición de venta correspondiente está activa.

El pago con vale tiene un diálogo embebido dentro del checkout, con cabecera y X propias: [SalePaymentCheckout.tsx:1072](../../frontend/packages/app-common/src/components/SalePaymentCheckout.tsx). No es un botón de limpiar el campo del vale.

### Ventanas abiertas desde documentos de venta

[SalesDocumentScreen.tsx:1319](../../frontend/packages/app-common/src/components/SalesDocumentScreen.tsx) cierra la ventana independiente del documento. La segunda ubicación, [SalesDocumentScreen.tsx:1600](../../frontend/packages/app-common/src/components/SalesDocumentScreen.tsx), cierra el editor temporal de nombre o precio del artículo dentro de ese documento.

## esPOS GESTIÓN

Hay 27 call sites de `WindowCloseButton` bajo `frontend/apps/app-gestion/src`. Los títulos son dinámicos cuando así se indica.

| Ventana visible | Ubicación |
|---|---|
| Configurar reglas de alertas | [ControlAlertsScreen.tsx:263](../../frontend/apps/app-gestion/src/ControlAlertsScreen.tsx) |
| Personalizar alertas de control | [ControlAlertsScreen.tsx:272](../../frontend/apps/app-gestion/src/ControlAlertsScreen.tsx) |
| Detalle de alerta/control con título suministrado por cada llamador; cierre deshabilitable según `closeDisabled` | [ControlAlertsScreen.tsx:752](../../frontend/apps/app-gestion/src/ControlAlertsScreen.tsx). Es una cabecera de una plantilla reutilizada. |
| Añadir o editar regla de control | [ControlAlertsScreen.tsx:964](../../frontend/apps/app-gestion/src/ControlAlertsScreen.tsx) |
| Detalle de familia, reutilizado por los diálogos de familia que pasan título y callback a la cabecera común | [FamiliesScreen.tsx:250](../../frontend/apps/app-gestion/src/FamiliesScreen.tsx) |
| Ventanas de usuario y roles del modal común de seguridad: formulario de usuario (alta/edición), crear/renombrar/eliminar rol y confirmaciones de activar/desactivar | [SecurityAdministrationScreen.tsx:646](../../frontend/apps/app-gestion/src/SecurityAdministrationScreen.tsx). El título y callback llegan por props a `Modal`; el cierre de confirmaciones se conserva en esa misma cabecera reutilizable. |
| Desbloqueo de grupo protegido (incluye configuración, fiscal y seguridad según grupo) | [GestionGroupUnlockDialog.tsx:84](../../frontend/apps/app-gestion/src/GestionGroupUnlockDialog.tsx). Durante `busy`, la X queda deshabilitada. |
| Detalle de cierre de caja | [CashActivityView.tsx:234](../../frontend/apps/app-gestion/src/CashActivityView.tsx) |
| Detalle de alerta de apertura/cierre de caja | [CashOpeningAlertsScreen.tsx:206](../../frontend/apps/app-gestion/src/CashOpeningAlertsScreen.tsx). Durante `saving`, la X queda deshabilitada. |
| Crear/modificar categoría de socio | [MemberCategoriesScreen.tsx:221](../../frontend/apps/app-gestion/src/MemberCategoriesScreen.tsx). Durante `busy`, la X queda deshabilitada. |
| Alta o detalle de representante/proveedor | [SupplierManagementScreen.tsx:485](../../frontend/apps/app-gestion/src/SupplierManagementScreen.tsx). Durante `saving`, la X queda deshabilitada. |
| Detalle de certificado VeriFactu | [VerifactuCertificateView.tsx:504](../../frontend/apps/app-gestion/src/VerifactuCertificateView.tsx). Durante `busy`, la X queda deshabilitada. |
| Historial de intentos VeriFactu | [VerifactuAttemptHistoryPanel.tsx:80](../../frontend/apps/app-gestion/src/VerifactuAttemptHistoryPanel.tsx) |
| Resolución de incidencia VeriFactu | [VerifactuResolutionPanel.tsx:189](../../frontend/apps/app-gestion/src/VerifactuResolutionPanel.tsx). Durante `submitting`, la X queda deshabilitada. |
| Filtros de registros VeriFactu | [FiscalWorkspaceDialog.tsx:120](../../frontend/apps/app-gestion/src/FiscalWorkspaceDialog.tsx), llamada desde `FiscalRecordsView`, `VerifactuDefectiveRecordsView` y `VerifactuManagementScreen`. |
| Exportación de registros fiscales y de cumplimiento | Misma cabecera de [FiscalWorkspaceDialog.tsx:120](../../frontend/apps/app-gestion/src/FiscalWorkspaceDialog.tsx), llamada desde `FiscalRecordsView` y `FiscalComplianceView`. En el export de registros, la X se deshabilita mientras se crea el trabajo. |
| Detalle ampliado de registro VeriFactu (drawer) | Misma cabecera reutilizada en [FiscalWorkspaceDialog.tsx:120](../../frontend/apps/app-gestion/src/FiscalWorkspaceDialog.tsx), llamada desde `FiscalRecordsView`; el título muestra el número del registro cuando está disponible. |
| Detalle de vale seleccionado | [VoucherManagementScreen.tsx:249](../../frontend/apps/app-gestion/src/VoucherManagementScreen.tsx) |
| Realizar ajuste de almacén | [WarehouseOperationsScreen.tsx:550](../../frontend/apps/app-gestion/src/WarehouseOperationsScreen.tsx) |
| Configuración de almacén y confirmación de eliminar/activar/desactivar | [WarehouseManagementScreen.tsx:212](../../frontend/apps/app-gestion/src/WarehouseManagementScreen.tsx), [WarehouseManagementScreen.tsx:279](../../frontend/apps/app-gestion/src/WarehouseManagementScreen.tsx) |

Las cabeceras de Gestión se apoyan en el estilo de sus diálogos. Donde el fondo es claro se activa la variante de contraste oscuro; no se cambia el color de fondo de la ventana. Se conservan los estados `disabled` y las acciones originales indicados arriba.

## Componentes comunes de catálogo, stock y almacén

Estos call sites viven en `app-common` y se reutilizan en flujos de gestión de productos, inventario, terceros y almacén; algunas pantallas reciben `app` y mantienen una variante PDA con botón textual:

| Ventana visible | Ubicación |
|---|---|
| Documentos del cliente | [CustomerDocumentsDialog.tsx:321](../../frontend/packages/app-common/src/components/CustomerDocumentsDialog.tsx). La variante `app === "pda"` sigue mostrando “Cerrar”. |
| Alta/detalle de tercero: cliente, proveedor o socio según `kind` | [PartyDirectoryPanel.tsx:1041](../../frontend/packages/app-common/src/components/PartyDirectoryPanel.tsx). PDA conserva su botón “Cerrar”. |
| Historial comercial del producto | [ProductCommercialHistory.tsx:161](../../frontend/packages/app-common/src/components/ProductCommercialHistory.tsx) |
| Crear/editar producto, selector de familia y selector de proveedor principal | [ProductCreateDialog.tsx:2147](../../frontend/packages/app-common/src/components/ProductCreateDialog.tsx), [ProductCreateDialog.tsx:2579](../../frontend/packages/app-common/src/components/ProductCreateDialog.tsx), [ProductCreateDialog.tsx:2761](../../frontend/packages/app-common/src/components/ProductCreateDialog.tsx). El contraste de los dos selectores depende de `classicWindow`. |
| Selección de objetivos/productos del asistente de promociones | [PromotionWizard.tsx:713](../../frontend/packages/app-common/src/components/PromotionWizard.tsx) |
| Retirada segura, con título y posibilidad de cierre sujetos al estado del diálogo | [SafeRetirementDialog.tsx:215](../../frontend/packages/app-common/src/components/SafeRetirementDialog.tsx) |
| Intercambiar código y código de barras | [StockScreen.tsx:6180](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Editor masivo de campo (título según el campo seleccionado) | [StockScreen.tsx:6253](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Buscador de producto del espacio de edición masiva | [StockScreen.tsx:6581](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Asignar proveedor al espacio masivo | [StockScreen.tsx:6648](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Elegir tipo de documento de compra para edición masiva | [StockScreen.tsx:6745](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Guardar lista, cambiar nombre de lista, comentarios, confirmación masiva y cerrar lista | [StockScreen.tsx:6842](../../frontend/packages/app-common/src/components/StockScreen.tsx), [StockScreen.tsx:6869](../../frontend/packages/app-common/src/components/StockScreen.tsx), [StockScreen.tsx:6913](../../frontend/packages/app-common/src/components/StockScreen.tsx), [StockScreen.tsx:6956](../../frontend/packages/app-common/src/components/StockScreen.tsx), [StockScreen.tsx:6979](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Filtros de ventas y de inventario | [StockScreen.tsx:7768](../../frontend/packages/app-common/src/components/StockScreen.tsx), [StockScreen.tsx:7943](../../frontend/packages/app-common/src/components/StockScreen.tsx). PDA usa su rama textual. |
| Selector de familia desde filtros de inventario y de stock | [StockScreen.tsx:8029](../../frontend/packages/app-common/src/components/StockScreen.tsx), [StockScreen.tsx:8079](../../frontend/packages/app-common/src/components/StockScreen.tsx). PDA usa su rama textual. |
| Información de la fila/producto del inventario | [StockScreen.tsx:8157](../../frontend/packages/app-common/src/components/StockScreen.tsx) |
| Ajuste de decimales de stock | [StockBulkDecimalDialog.tsx:49](../../frontend/packages/app-common/src/components/StockBulkDecimalDialog.tsx) |
| Selección de familias para edición masiva | [StockBulkFamilyDialog.tsx:118](../../frontend/packages/app-common/src/components/StockBulkFamilyDialog.tsx) |
| Filtros de edición masiva | [StockBulkFilterDialog.tsx:129](../../frontend/packages/app-common/src/components/StockBulkFilterDialog.tsx) |
| Reglas de precio de edición masiva | [StockBulkPriceRulesDialog.tsx:623](../../frontend/packages/app-common/src/components/StockBulkPriceRulesDialog.tsx). PDA conserva “Cerrar”; la X puede estar deshabilitada por `busy`. |
| Permisos de edición masiva | [StockPermissionsDialog.tsx:248](../../frontend/packages/app-common/src/components/StockPermissionsDialog.tsx). PDA conserva “Cerrar”; la X puede estar deshabilitada por `saving`. |
| Ajustes de inventario | [StockSettingsDialog.tsx:365](../../frontend/packages/app-common/src/components/StockSettingsDialog.tsx). PDA conserva “Cerrar”. |
| Cabecera superior del documento de almacén y dos editores de línea (rápido y completo) | [WarehouseDocumentDialog.tsx:1803](../../frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx), [WarehouseDocumentDialog.tsx:2078](../../frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx), [WarehouseDocumentDialog.tsx:2120](../../frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx). Los tres mantienen el botón textual de PDA. |
| Alta/edición de proveedor de almacén | [WarehouseSupplierDialog.tsx:187](../../frontend/packages/app-common/src/components/WarehouseSupplierDialog.tsx). La X conserva `disabled={saving}`. |
| Indicador/ventana de estado VeriFactu para TPV | [VerifactuPosIndicator.tsx:258](../../frontend/packages/app-common/src/components/VerifactuPosIndicator.tsx) |

## Reutilización y variantes

- `SaleActionDialog`: 1 control fuente, 13 títulos de operación enumerados arriba.
- `FiscalWorkspaceDialog`: 1 control fuente, utilizado para filtros de registros, filtros de cola y defectuosos, exportación de registros, exportación de cumplimiento y detalle de registro.
- `SecurityAdministrationScreen`: 1 control fuente en `Modal`, compartido por diálogos de usuarios, roles y confirmación.
- `ControlAlertsScreen`: una cabecera genérica para detalles cuyo título llega por prop; además hay cabeceras independientes para configurar reglas, preferencias y alta/edición de regla.
- `ProductCreateDialog`, `StockScreen`, `SalesReportScreen`, `CustomerPendingSaleDialog`, `SalesDocumentScreen` y `WarehouseDocumentDialog` contienen más de un call site y/o varias ventanas embebidas; cada título/ubicación se detalla en sus tablas.
- El recuento vigente es de 113 call sites JSX: 86 en `app-common` y 27 en `app-gestion`. `app-venta/src` no contiene usos directos; VENTA consume los componentes comunes. El recuento mide puntos de renderizado, no instancias simultáneas.

## Omisiones legítimas

Los botones de pie “Cancelar”, “Cerrar” y “Salir” siguen siendo acciones propias y no se cuentan como controles X. Las ramas PDA que muestran “Cerrar” se conservan. También quedan fuera los botones para limpiar campos, etiquetas o chips de filtros, los controles de vaciado de búsqueda, acciones de menú y cierres propios del teclado táctil/escritura. Se omiten diálogos de elección obligatoria sin salida cancelable, formularios principales a pantalla completa sin cabecera de ventana, paneles inline y avisos que solo permiten aceptar (por ejemplo, `CashPaymentValidationDialog`). Las X de confirmaciones cancelables invocan la misma salida segura que el control de permanencia/cancelación existente; no sustituyen ni alteran la operación de negocio.

## Ampliación del alcance: nueve ventanas comunes usadas en VENTA

Estos nueve call sites comunes incorporan X de cabecera aunque antes el cierre solo estaba en acciones inferiores. La X usa el callback de salida ya existente y respeta el estado ocupado cuando se indica.

| Ventana visible | Ubicación y cierre conservado |
|---|---|
| Etiquetas de producto (composición/impresión) | [SaleProductLabelDialog.tsx:663](../../frontend/packages/app-common/src/components/SaleProductLabelDialog.tsx); `onClose`, deshabilitada mientras `busy`. |
| Desglose de efectivo | [CashDenominationDialog.tsx:161](../../frontend/packages/app-common/src/components/CashDenominationDialog.tsx); `onCancel`; escritorio solamente, sin estado busy en el control. |
| Importación compartida desde Excel | [SharedExcelImportDialog.tsx:1204](../../frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx); `handleClose`, deshabilitada durante `isApplying`. La confirmación maestra de cambios y la confirmación de cierre se detallan más abajo. |
| Modelo 347 del cliente | [CustomerModel347Dialog.tsx:98](../../frontend/packages/app-common/src/components/CustomerModel347Dialog.tsx); `close`, que sigue la salida existente y aborta la petición activa si procede. |
| Generador y comprobador de EAN | [SaleInternalEanDialog.tsx:227](../../frontend/packages/app-common/src/components/SaleInternalEanDialog.tsx); `onClose`, deshabilitada mientras `busy`. |
| Entrada o retirada de efectivo | [SaleCashWithdrawalDialog.tsx:265](../../frontend/packages/app-common/src/components/SaleCashWithdrawalDialog.tsx); `finishDialog`, deshabilitada mientras `busy`; conserva el resultado ya registrado o la cancelación según el estado previo. |
| Introducir precio | [SaleOpenPriceDialog.tsx:78](../../frontend/packages/app-common/src/components/SaleOpenPriceDialog.tsx); `onCancel`. |
| Procesando pago con tarjeta | [CardPaymentDialog.tsx:23](../../frontend/packages/app-common/src/components/CardPaymentDialog.tsx); `onCancel`, deshabilitada durante `submitting`. |
| Cobro con tarjeta manual | [ManualCardReferenceDialog.tsx:27](../../frontend/packages/app-common/src/components/ManualCardReferenceDialog.tsx); `onCancel`, deshabilitada mientras `busy`. |

## Confirmaciones y cierres cancelables compartidos

| Ventana visible | Ubicación | Salida de cabecera |
|---|---|---|
| Confirmaciones genéricas de la aplicación (`ErpConfirmDialog`) | [ErpConfirmDialog.tsx:24](../../frontend/packages/app-common/src/components/ErpConfirmDialog.tsx) | `onCancel`, deshabilitada con `busy`; coincide con la salida segura ya ofrecida por el diálogo. |
| Importación Excel: confirmar cambios maestros | [SharedExcelImportDialog.tsx:1507](../../frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx) | Limpia `masterConfirmationCount` como la cancelación existente. |
| Importación Excel: confirmar cierre con cambios pendientes | [SharedExcelImportDialog.tsx:1535](../../frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx) | `cancelClose`, que devuelve al diálogo de importación y mantiene su retorno de foco. |
| Volver desde Inicio con cambios de venta | [AppVentaHomeEscapeNavigation.tsx:93](../../frontend/packages/app-common/src/components/AppVentaHomeEscapeNavigation.tsx) | `cancelNavigation`; solo escritorio. |
| Confirmar apagado desde acceso/login | [LoginScreen.tsx:294](../../frontend/packages/app-common/src/components/LoginScreen.tsx) | Cierra la confirmación mediante `setShutdownOpen(false)`. |
| Confirmar apagado desde controles de sesión | [SessionTopControls.tsx:179](../../frontend/packages/app-common/src/components/SessionTopControls.tsx) | `setShutdownOpen(false)`; deshabilitada mientras `shutdownPreparing`. |
| Confirmar apagado desde el informe de ventas | [SalesReportScreen.tsx:4470](../../frontend/packages/app-common/src/components/SalesReportScreen.tsx) | Cierra la confirmación mediante `setShutdownOpen(false)`. |
| Confirmar cancelación de inventario en curso | [StockCountScreen.tsx:77](../../frontend/apps/app-gestion/src/StockCountScreen.tsx) | Conserva el callback de cancelar conteo; respeta el estado busy del diálogo. |
| Confirmar diferencias del conteo | [WarehouseOperationsScreen.tsx:647](../../frontend/apps/app-gestion/src/WarehouseOperationsScreen.tsx) | Cierra la confirmación con `setConfirmingCount(false)`; deshabilitada mientras se guarda. |
| Confirmar restablecimiento de seguridad de operación | [SalesOperationSecurityScreen.tsx:498](../../frontend/apps/app-gestion/src/SalesOperationSecurityScreen.tsx) | Cierra la confirmación con `setResetConfirmationOpen(false)`; deshabilitada mientras hay `busy`. |

La cabecera de `ErpConfirmDialog` cubre también estas ventanas concretas:

- Confirmar activación, desactivación o desvinculación de un representante del proveedor.
- Confirmar activación/desactivación de cliente, proveedor o socio, y salida con cambios pendientes en su ficha.
- Confirmar la eliminación de una imagen del producto.
- Cambios pendientes en configuración. Conserva la salida existente de «Cancelar»/Escape, que descarta los cambios y continúa la navegación; este trabajo no modifica esa regla.

## Documento de conteo de stock en GESTIÓN

La ventana de documento de conteo tiene cinco cabeceras/cierres en [StockCountDocumentWindow.tsx](../../frontend/apps/app-gestion/src/StockCountDocumentWindow.tsx): ventana principal (línea 133), revisión del conteo (186), edición de familia (191), confirmación de salida con cambios pendientes (193) y confirmación de recarga (195). Cada X conserva su callback de cierre propio; revisión y cierre/recarga respetan sus estados busy cuando están activos. La ventana de familia permite volver al documento, y las confirmaciones cancelables permiten permanecer en él.

## Variantes por título de modales compartidos de GESTIÓN

`FamiliesScreen` mantiene una cabecera común en [FamiliesScreen.tsx:250](../../frontend/apps/app-gestion/src/FamiliesScreen.tsx), reutilizada en cuatro grupos de ventanas: alta/edición de familia o subfamilia (línea 1863; título traducido según creación/edición y entidad), mover productos (2091), mover productos a GENERAL (2323) e impacto de borrado (2363). El cierre de esta cabecera se deshabilita con `busy`.

`SecurityAdministrationScreen` usa la cabecera de `Modal` en [SecurityAdministrationScreen.tsx:646](../../frontend/apps/app-gestion/src/SecurityAdministrationScreen.tsx). Sus títulos abarcan crear usuario, editar identidad, cambiar rol, restablecer PIN y configurar límite de descuento (línea 342); confirmar activar/desactivar usuario (271); y crear, renombrar o eliminar rol (558, 580 y 612). La cabecera conserva el callback de cada modal/confirmación; el componente común no impone un bloqueo adicional.

`VerifactuCertificateView` comparte una cabecera en [VerifactuCertificateView.tsx:504](../../frontend/apps/app-gestion/src/VerifactuCertificateView.tsx) para importar certificado VERI*FACTU, sustituirlo y eliminarlo (call sites 337 y 444). En las tres variantes la X conserva `onClose` y queda deshabilitada mientras `busy`/`submitting`.
