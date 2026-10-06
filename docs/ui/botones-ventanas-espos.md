# Botones de ventanas de esPOS

Actualizado el 06/10/2026 en la rama codex/venta-login-ui. El inventario inicial de colores abarcó 307 ubicaciones JSX de botones en 80 componentes o pantallas. Un componente puede representar varias ventanas y varias ubicaciones pueden mostrarse según el estado. Las excepciones posteriores se detallan a continuación.

Aceptar, insertar, guardar, aplicar, seleccionar y confirmar usan #07254F. En las ventanas normales de escritorio se retiran los botones inferiores Cerrar/Cancelar; la salida usa la cruz de cabecera y los atajos existentes. Los avisos y confirmaciones conservan Cancelar/Cerrar en #B42318. Las acciones negativas que ya eran destructivas conservan el rojo. Los botones de acción tienen texto blanco, borde sólido de 1 px y el radio compartido de 3 px.

Se mantienen los manejadores, validaciones, estados deshabilitados, foco, navegación con teclado y traducciones ES/EN/ZH. El formato de la cruz de cabecera se conserva. Las acciones auxiliares del cuerpo, selectores, pestañas y menús de navegación no se han incluido en este patrón.

La hoja compartida es [DialogActionButtons.css](../../frontend/packages/app-common/src/components/DialogActionButtons.css), importada desde tpv.css. Las clases erp-dialog-action-confirm y erp-dialog-action-cancel definen los colores de los botones y prevalecen sobre los colores antiguos de cada ventana. Las confirmaciones destructivas pueden usar la clase roja; el cierre de la ventana se identifica por separado.

## Distribución de acciones de ventana

En los avisos y confirmaciones, los botones Cerrar/Cancelar se mantienen a la izquierda. Aceptar, Insertar, Seleccionar, Guardar y Confirmar se colocan a la derecha, alineados en la misma fila. Las ventanas normales conservan únicamente sus acciones de confirmación en el pie. Las acciones auxiliares y las confirmaciones destructivas mantienen su función y el rojo cuando corresponde.

El ajuste inicial de distribución abarcó 124 ubicaciones de cierre/cancelación y 120 filas de acciones en 70 archivos de los componentes comunes, APP VENTA y APP GESTIÓN. La clase `erp-dialog-actions-row` identifica la fila y `erp-dialog-dismiss` identifica exclusivamente el cierre o cancelación de esa ventana. Los grupos de botones anidados usan `erp-dialog-actions-group`; los resúmenes y ayudas se conservan entre ambos extremos. Se conserva el orden de teclado Cancelar/Cerrar antes de confirmar al reordenar los botones.

Las cruces de cabecera y el botón de mostrar/ocultar teclado mantienen su formato y posición aprobados. Los selectores de fecha y las acciones internas de formularios no se incluyen en la distribución de las ventanas. El inventario vigente de cierres inferiores está en [cierres-ventanas-espos.md](cierres-ventanas-espos.md).

## Ajustes posteriores de teclado y factura

El cierre del teclado contextual se sustituye por `TouchKeyboardToggle`: botón blanco, icono de teclado y triángulo hacia abajo cuando está abierto o hacia arriba cuando está oculto. Permanece visible para volver a abrir el teclado; las etiquetas accesibles y el título se traducen en ES/EN/ZH. Cobro en efectivo utiliza el mismo componente para su teclado propio. Se preservan el campo activo, su selección, el borrador numérico y los bloqueos durante el cobro.

En Convertir ticket a factura, Crear factura usa fondo y borde #07254F y texto blanco, con el radio de 3 px. Se elimina exclusivamente el botón Cancelar de su pie. La cruz de cabecera y Escape mantienen el cierre existente y su bloqueo durante una operación.

La ventana de factura usa fondo #E7EDF3 en cuerpo y teclado, sin sus líneas de separación. El campo y los datos del ticket se agrupan en un recuadro blanco; Cliente fiscal usa otro recuadro blanco que incluye Crear factura y termina encima del botón del teclado. Su contenido interior puede desplazarse y las acciones permanecen visibles dentro del mismo recuadro. Lista de clientes abre el buscador y la tabla compartidos con Fin, con las mismas siete columnas y ordenación. Al elegir un cliente activo, vuelve a la factura y lo establece como cliente fiscal; Escape cierra solo el listado. El listado de Fin conserva sus acciones, permisos y reglas existentes.

La ventana mantiene 1000 × 900 px, limitada al espacio disponible de la pantalla. Los resultados del buscador fiscal se superponen bajo el campo, con desplazamiento interno y límite inferior en el borde inferior de Crear factura; no cambian el tamaño de la ventana ni desplazan sus recuadros y acciones. Escape o una pulsación exterior cierran solo el desplegable. El buscador es más estrecho, Lista de clientes queda a su derecha en la misma fila y se elimina el texto Máximo 25 resultados. La cabecera fiscal y el buscador tienen menos separación; el límite real de búsqueda sigue siendo 25 clientes.

El teclado alfanumérico de esta ventana tiene fondo blanco, sin marco ni separador azul, radio exterior de 8 px y sombra suave. Las teclas usan borde gris claro y radio de 4 px. Su tipografía es Helvetica con Arial y sans-serif como alternativas; si Helvetica no está instalada, se utiliza Arial. Se conservan tamaños, distribución y comportamiento del teclado.

## Componentes comunes de Venta y Gestión

La calculadora mantiene siempre visible su teclado numérico, tanto en modo táctil como con teclado físico, sin botón de plegado. La primera fila es C, CE, %, ÷; el 0 ocupa las dos primeras celdas de la última fila. El visor no contiene un botón de borrar; Retroceso sigue disponible desde el teclado físico.

| Ventanas o función | Componente | Botones |
|---|---|---:|
| Confirmación para volver a Inicio | [AppVentaHomeEscapeNavigation.tsx](../../frontend/packages/app-common/src/components/AppVentaHomeEscapeNavigation.tsx) | 2 |
| Pago con tarjeta: cancelación o cierre | [CardPaymentDialog.tsx](../../frontend/packages/app-common/src/components/CardPaymentDialog.tsx) | 1 |
| Recuento de efectivo | [CashDenominationDialog.tsx](../../frontend/packages/app-common/src/components/CashDenominationDialog.tsx) | 2 |
| Pago en efectivo | [CashPaymentDialog.tsx](../../frontend/packages/app-common/src/components/CashPaymentDialog.tsx) | 2 |
| Resultado del cobro y reintento de impresión | [CashPaymentResultDialog.tsx](../../frontend/packages/app-common/src/components/CashPaymentResultDialog.tsx) | 3 |
| Validación del cobro | [CashPaymentValidationDialog.tsx](../../frontend/packages/app-common/src/components/CashPaymentValidationDialog.tsx) | 1 |
| Documentos del cliente | [CustomerDocumentsDialog.tsx](../../frontend/packages/app-common/src/components/CustomerDocumentsDialog.tsx) | 2 |
| Modelo 347 del cliente | [CustomerModel347Dialog.tsx](../../frontend/packages/app-common/src/components/CustomerModel347Dialog.tsx) | 2 |
| Autorización de pago de documento de venta y ventana de venta pendiente | [CustomerPendingSaleDialog.tsx](../../frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx) | 6 |
| Consulta/gestión de cobros pendientes, ventana principal e historial de detalle | [CustomerReceivablesScreen.tsx](../../frontend/packages/app-common/src/components/CustomerReceivablesScreen.tsx) | 3 |
| Confirmaciones comunes reutilizadas por las pantallas | [ErpConfirmDialog.tsx](../../frontend/packages/app-common/src/components/ErpConfirmDialog.tsx) | 2 |
| Emisión de ticket regalo | [GiftReceiptDialog.tsx](../../frontend/packages/app-common/src/components/GiftReceiptDialog.tsx) | 3 |
| Confirmación de apagado | [LoginScreen.tsx](../../frontend/packages/app-common/src/components/LoginScreen.tsx) | 2 |
| Referencia de pago manual con tarjeta | [ManualCardReferenceDialog.tsx](../../frontend/packages/app-common/src/components/ManualCardReferenceDialog.tsx) | 2 |
| Monedero del socio | [MemberWalletDialog.tsx](../../frontend/packages/app-common/src/components/MemberWalletDialog.tsx) | 2 |
| Ventas aparcadas | [ParkedSalesDialog.tsx](../../frontend/packages/app-common/src/components/ParkedSalesDialog.tsx) | 5 |
| Alta/detalle de tercero: cliente, proveedor o socio según `kind` | [PartyDirectoryPanel.tsx](../../frontend/packages/app-common/src/components/PartyDirectoryPanel.tsx) | 6 |
| Asignación de pagos/cobros del documento, título y texto accesible según el contexto | [PaymentAllocationPanel.tsx](../../frontend/packages/app-common/src/components/PaymentAllocationPanel.tsx) | 4 |
| Historial comercial del producto | [ProductCommercialHistory.tsx](../../frontend/packages/app-common/src/components/ProductCommercialHistory.tsx) | 1 |
| Crear/editar producto, selector de familia y selector de proveedor principal | [ProductCreateDialog.tsx](../../frontend/packages/app-common/src/components/ProductCreateDialog.tsx) | 6 |
| Selección de objetivos/productos del asistente de promociones | [PromotionWizard.tsx](../../frontend/packages/app-common/src/components/PromotionWizard.tsx) | 3 |
| Retirada segura, con título y posibilidad de cierre sujetos al estado del diálogo | [SafeRetirementDialog.tsx](../../frontend/packages/app-common/src/components/SafeRetirementDialog.tsx) | 2 |
| Calculadora de venta | [SaleCalculatorDialog.tsx](../../frontend/packages/app-common/src/components/SaleCalculatorDialog.tsx) | 1 |
| Autorización de apertura/cierre de cajón | [SaleCashDrawerAuthorizationDialog.tsx](../../frontend/packages/app-common/src/components/SaleCashDrawerAuthorizationDialog.tsx) | 2 |
| Sesión de caja (solo cuando no está embebida) | [SaleCashSessionDialog.tsx](../../frontend/packages/app-common/src/components/SaleCashSessionDialog.tsx) | 3 |
| Retirada de efectivo y confirmación | [SaleCashWithdrawalDialog.tsx](../../frontend/packages/app-common/src/components/SaleCashWithdrawalDialog.tsx) | 4 |
| Alta de cliente desde venta | [SaleCustomerCreateDialog.tsx](../../frontend/packages/app-common/src/components/SaleCustomerCreateDialog.tsx) | 2 |
| Consulta de recibos/cobros del cliente | [SaleCustomerReceivablesDialog.tsx](../../frontend/packages/app-common/src/components/SaleCustomerReceivablesDialog.tsx) | 3 |
| Código EAN interno | [SaleInternalEanDialog.tsx](../../frontend/packages/app-common/src/components/SaleInternalEanDialog.tsx) | 1 |
| Autorización de modificación de venta | [SaleMutationAuthorizationDialog.tsx](../../frontend/packages/app-common/src/components/SaleMutationAuthorizationDialog.tsx) | 2 |
| Precio abierto del producto | [SaleOpenPriceDialog.tsx](../../frontend/packages/app-common/src/components/SaleOpenPriceDialog.tsx) | 2 |
| Cobro, vale y autorizaciones del checkout | [SalePaymentCheckout.tsx](../../frontend/packages/app-common/src/components/SalePaymentCheckout.tsx) | 8 |
| Consulta de producto | [SaleProductConsultationDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductConsultationDialog.tsx) | 1 |
| Información del producto desde venta | [SaleProductInformationDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductInformationDialog.tsx) | 2 |
| Etiquetas de producto (composición/impresión) | [SaleProductLabelDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductLabelDialog.tsx) | 8 |
| Historial de ventas del producto | [SaleProductSalesHistoryDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductSalesHistoryDialog.tsx) | 1 |
| Búsqueda/selección de producto | [SaleProductSearchDialog.tsx](../../frontend/packages/app-common/src/components/SaleProductSearchDialog.tsx) | 1 |
| Comentario, cliente, descuentos, precio, cantidad, nombre temporal, impresión y confirmaciones de eliminar venta o líneas | [SaleScreen.tsx](../../frontend/packages/app-common/src/components/SaleScreen.tsx) | 26 |
| Selección de cliente para documento de venta | [SalesDocumentCustomerDialog.tsx](../../frontend/packages/app-common/src/components/SalesDocumentCustomerDialog.tsx) | 2 |
| Borrador de documento de venta | [SalesDocumentDraftDialog.tsx](../../frontend/packages/app-common/src/components/SalesDocumentDraftDialog.tsx) | 2 |
| Editor temporal de nombre y precio del documento de venta | [SalesDocumentScreen.tsx](../../frontend/packages/app-common/src/components/SalesDocumentScreen.tsx) | 2 |
| Selección de número de serie/lote | [SaleSerialNumberDialog.tsx](../../frontend/packages/app-common/src/components/SaleSerialNumberDialog.tsx) | 2 |
| Rectificación de factura | [SalesInvoiceRectificationDialog.tsx](../../frontend/packages/app-common/src/components/SalesInvoiceRectificationDialog.tsx) | 3 |
| Informe de ventas: actividad, vista previa de documento y filtros | [SalesReportScreen.tsx](../../frontend/packages/app-common/src/components/SalesReportScreen.tsx) | 10 |
| Cancelación de ticket | [SaleTicketCancellationDialog.tsx](../../frontend/packages/app-common/src/components/SaleTicketCancellationDialog.tsx) | 3 |
| Factura de ticket | [SaleTicketInvoiceDialog.tsx](../../frontend/packages/app-common/src/components/SaleTicketInvoiceDialog.tsx) | 3 |
| Cierre de paneles táctiles auxiliares | [SaleTouchControls.tsx](../../frontend/packages/app-common/src/components/SaleTouchControls.tsx) | 1 |
| Cierre del teclado táctil de las ventanas | [SaleTouchKeyboardScope.tsx](../../frontend/packages/app-common/src/components/SaleTouchKeyboardScope.tsx) | 1 |
| Importación de Excel, selección y confirmaciones | [SharedExcelImportDialog.tsx](../../frontend/packages/app-common/src/components/SharedExcelImportDialog.tsx) | 10 |
| Ajuste de decimales de stock | [StockBulkDecimalDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkDecimalDialog.tsx) | 2 |
| Selección de familias y subfamilias en edición masiva | [StockBulkFamilyDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkFamilyDialog.tsx) | 2 |
| Filtros de edición masiva | [StockBulkFilterDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkFilterDialog.tsx) | 2 |
| Reglas de precios y confirmaciones de edición masiva | [StockBulkPriceRulesDialog.tsx](../../frontend/packages/app-common/src/components/StockBulkPriceRulesDialog.tsx) | 4 |
| Permisos y seguridad de stock | [StockPermissionsDialog.tsx](../../frontend/packages/app-common/src/components/StockPermissionsDialog.tsx) | 3 |
| Intercambiar código y código de barras | [StockScreen.tsx](../../frontend/packages/app-common/src/components/StockScreen.tsx) | 30 |
| Configuración de stock y mínimos | [StockSettingsDialog.tsx](../../frontend/packages/app-common/src/components/StockSettingsDialog.tsx) | 3 |
| Gestión de tickets | [TicketManagementDialog.tsx](../../frontend/packages/app-common/src/components/TicketManagementDialog.tsx) | 2 |
| Devolución de ticket | [TicketReturnDialog.tsx](../../frontend/packages/app-common/src/components/TicketReturnDialog.tsx) | 2 |
| Documento de almacén: línea, proveedor, descuentos, guardado y confirmaciones | [WarehouseDocumentDialog.tsx](../../frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx) | 9 |
| Selección y filtros del proveedor | [WarehouseSupplierDialog.tsx](../../frontend/packages/app-common/src/components/WarehouseSupplierDialog.tsx) | 4 |

## Ventanas propias de esPOS VENTA

| Ventanas o función | Componente | Botones |
|---|---|---:|
| Cierre de ventanas auxiliares de venta sin contexto o sin sesión | [main.tsx](../../frontend/apps/app-venta/src/main.tsx) | 3 |

## Ventanas propias de esPOS GESTIÓN

| Ventanas o función | Componente | Botones |
|---|---|---:|
| Detalle de alerta de apertura/cierre de caja | [CashOpeningAlertsScreen.tsx](../../frontend/apps/app-gestion/src/CashOpeningAlertsScreen.tsx) | 1 |
| Configurar reglas de alertas | [ControlAlertsScreen.tsx](../../frontend/apps/app-gestion/src/ControlAlertsScreen.tsx) | 5 |
| Detalle de familia, reutilizado por los diálogos de familia que pasan título y callback a la cabecera común | [FamiliesScreen.tsx](../../frontend/apps/app-gestion/src/FamiliesScreen.tsx) | 8 |
| Exportación de cumplimiento fiscal | [FiscalComplianceView.tsx](../../frontend/apps/app-gestion/src/FiscalComplianceView.tsx) | 2 |
| Filtros y exportación de registros fiscales | [FiscalRecordsView.tsx](../../frontend/apps/app-gestion/src/FiscalRecordsView.tsx) | 4 |
| Desbloqueo de grupo protegido (incluye configuración, fiscal y seguridad según grupo) | [GestionGroupUnlockDialog.tsx](../../frontend/apps/app-gestion/src/GestionGroupUnlockDialog.tsx) | 2 |
| Crear/modificar categoría de socio | [MemberCategoriesScreen.tsx](../../frontend/apps/app-gestion/src/MemberCategoriesScreen.tsx) | 2 |
| Configuración de seguridad de operaciones de venta | [SalesOperationSecurityScreen.tsx](../../frontend/apps/app-gestion/src/SalesOperationSecurityScreen.tsx) | 2 |
| Ventanas de usuario y roles del modal común de seguridad: formulario de usuario (alta/edición), crear/renombrar/eliminar rol y confirmaciones de activar/desactivar | [SecurityAdministrationScreen.tsx](../../frontend/apps/app-gestion/src/SecurityAdministrationScreen.tsx) | 10 |
| Documento de inventario: productos y confirmaciones auxiliares | [StockCountDocumentWindow.tsx](../../frontend/apps/app-gestion/src/StockCountDocumentWindow.tsx) | 9 |
| Creación de documento de inventario | [StockCountScreen.tsx](../../frontend/apps/app-gestion/src/StockCountScreen.tsx) | 2 |
| Alta o detalle de representante/proveedor | [SupplierManagementScreen.tsx](../../frontend/apps/app-gestion/src/SupplierManagementScreen.tsx) | 2 |
| Detalle de certificado VeriFactu | [VerifactuCertificateView.tsx](../../frontend/apps/app-gestion/src/VerifactuCertificateView.tsx) | 4 |
| Filtros de registros VeriFactu defectuosos | [VerifactuDefectiveRecordsView.tsx](../../frontend/apps/app-gestion/src/VerifactuDefectiveRecordsView.tsx) | 1 |
| Filtros de gestión VeriFactu | [VerifactuManagementScreen.tsx](../../frontend/apps/app-gestion/src/VerifactuManagementScreen.tsx) | 1 |
| Resolución de incidencia VeriFactu | [VerifactuResolutionPanel.tsx](../../frontend/apps/app-gestion/src/VerifactuResolutionPanel.tsx) | 4 |
| Detalle de vale seleccionado | [VoucherManagementScreen.tsx](../../frontend/apps/app-gestion/src/VoucherManagementScreen.tsx) | 2 |
| Configuración de almacén y confirmación de eliminar/activar/desactivar | [WarehouseManagementScreen.tsx](../../frontend/apps/app-gestion/src/WarehouseManagementScreen.tsx) | 8 |
| Realizar ajuste de almacén | [WarehouseOperationsScreen.tsx](../../frontend/apps/app-gestion/src/WarehouseOperationsScreen.tsx) | 4 |

## Comprobaciones

- TypeScript de APP VENTA y APP GESTIÓN y compilaciones Vite correctos.
- Diez pruebas existentes de números de serie, diálogos masivos de stock y desbloqueo de grupos correctas.
- Componentes reales revisados en navegador: acciones de Venta, Stock, Gestión y confirmación común; colores, radio, foco y estado deshabilitado comprobados. Acciones traducidas revisadas en ES/EN/ZH.
- Presupuestos de los bundles dentro de sus límites. APP VENTA mantiene el aviso preventivo de proximidad al límite CSS.
- No se realizaron cobros, devoluciones, cambios de datos ni llamadas reales desde las vistas de revisión.

## Teclados táctiles compartidos · 06/10/2026

Todos los teclados virtuales existentes usan superficie blanca, radio exterior 8 px, sombra suave y teclas redondeadas con borde gris. Fuente Helvetica, Arial, sans-serif, con Arial como alternativa en este equipo. Los teclados de cobro/calculadora conservan colores funcionales de sus acciones especiales.

El botón blanco con icono de teclado y triángulo permanece presente en las ventanas táctiles con teclado, también plegado o antes de enfocar un campo. El texto que identifica el campo activo o el teclado solo aparece al desplegarlo; el botón permanece alineado a la derecha. Las teclas plegadas permanecen montadas y deshabilitadas; se conservan texto, cursor y borrador decimal. Se evitan controles duplicados entre teclados explícitos y SaleTouchKeyboardScope. Los campos excluidos, las ventanas inactivas y el escáner principal mantienen sus restricciones.

Limpiar todo se sitúa a la izquierda del botón de ocultar teclado y solo aparece con las teclas desplegadas. Limpia únicamente el campo activo, también en el inicio de sesión y en los teclados de cobro; no elimina pagos registrados. Enter ocupa el lugar de la antigua tecla Limpiar en los teclados alfanuméricos y numéricos. Reutiliza los handlers del Enter físico, respeta validaciones y acciones deshabilitadas y añade un salto de línea en textarea. En recuento de caja conserva el avance existente por denominaciones. La calculadora conserva sus acciones C y CE y su teclado siempre visible.

Cobertura: buscadores y selección de clientes/productos, factura, historial, consulta de precio, devolución, comentarios, número de serie y autorización; precio/cantidad/descuentos y producto temporal; cobro, efectivo, calculadora y recuento de caja. No se incorporan teclados virtuales nuevos a APP GESTIÓN ni SaaS.

El inicio de sesión desktop de APP VENTA también incorpora el teclado compartido. Su botón está siempre disponible antes de autenticar y el teclado comienza plegado. El campo activo alterna entre Usuario y Contraseña; se conserva foco, cursor y texto. La contraseña permanece oculta. El teclado se bloquea durante el acceso y no se incorpora al login de Gestión ni al acceso PDA embedded.

En el inicio de sesión de APP VENTA, el botón blanco de teclado se sitúa dentro del cuadro principal, alineado a la derecha entre Contraseña y Entrar. Se elimina el nombre del campo activo junto al teclado. El control conserva su tipo button para no enviar el formulario.
