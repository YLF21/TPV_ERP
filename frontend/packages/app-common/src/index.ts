export { authenticate, authenticateRemote, canAccessApp, hasPermission } from "./auth/auth";
export { apiRequest, checkBackendConnection, ApiConnectionError, ApiError } from "./api/client";
export { apiBaseUrl, devTerminalContext } from "./api/runtime";
export { loadTerminalIdentity, resolveTerminalIdentity } from "./terminalIdentity";
export type { TerminalIdentityBridge, TerminalIdentityLoadResult } from "./terminalIdentity";
export { AppFrame } from "./components/AppFrame";
export { ErpSelect } from "./components/ErpSelect";
export type { ErpSelectOption } from "./components/ErpSelect";
export { LoginScreen } from "./components/LoginScreen";
export { PromotionListScreen } from "./components/PromotionListScreen";
export { visibleSalesReports } from "./components/salesReportAccess";
export { visibleStockViewsForSession, userCanManageWarehouses } from "./components/stockAccess";
export type { StockViewKey } from "./components/stockAccess";
export { visibleWarehouseSectionsForSession, warehouseSections } from "./components/warehouseAccess";
export type { WarehouseSection } from "./components/warehouseAccess";
export { TableLayoutHeaderCell } from "./components/TableLayoutHeaderCell";
export { TableSortButton } from "./components/TableSortButton";
export {
  compareTableSortValues,
  nextTableSort,
  readStoredTableSort,
  sanitizeTableSort,
  sortTableRows,
  tableSortStorageKey,
  useTableSortPreference
} from "./components/tableSorting";
export type { TableSort, TableSortDirection, TableSortValue } from "./components/tableSorting";
export { useTableLayoutPreference } from "./components/useTableLayoutPreference";
export { tableLayoutGridTemplate, visibleTableColumns } from "./components/tableLayoutPreferences";
export type { TableColumnDefinition } from "./components/tableLayoutPreferences";
export type { PartyDirectoryKind } from "./components/PartyDirectoryPanel";
export {
  createHardwareUnavailableResult,
  createTestTicket,
  defaultHardwareConfig,
  getHardwareBridge
} from "./hardware/hardware";
export { createTranslator, LocalizedMessages, messages } from "./i18n/LocalizedMessages";
export type { AppKind, LocaleCode, Permission, TerminalContext, UserSession } from "./types";
export { addLocalDays, pendingCreateBody, pendingSummary } from "./sale/customerReceivables";
export { resolvePendingCardPaymentMode } from "./sale/customerReceivables";
export { useMemberBalanceReservation } from "./sale/memberBalanceReservation";
export type {
  MemberBalanceReservationState,
  MemberBalanceReservationStatus,
} from "./sale/memberBalanceReservation";
export type {
  PendingCardPaymentMode,
  PendingPaymentAllocation,
  PendingSaleDraft,
  PendingTerminalPaymentConfiguration,
} from "./sale/customerReceivables";
export {
  defaultCheckoutPaymentMethodConfiguration,
  isReferenceConfigurablePaymentMethod,
  loadPaymentMethods,
  managedCheckoutPaymentMethodNames,
  referenceConfigurablePaymentMethodNames,
  resolveCheckoutPaymentMethodConfiguration,
  setPaymentMethodActive,
  setPaymentMethodReferenceRequirement,
} from "./sale/paymentMethods";
export type {
  CheckoutPaymentMethodConfiguration,
  PaymentMethodView,
} from "./sale/paymentMethods";
export {
  addInvoiceBankAccount,
  loadInvoicePrintConfiguration,
  saveInvoiceObservations,
  setInvoiceBankAccountActive,
} from "./sale/invoicePrintConfiguration";
export type {
  InvoiceBankAccountView,
  InvoicePrintConfigurationView,
} from "./sale/invoicePrintConfiguration";
export {
  findSaleOperationAuthorization,
  loadSalesOperationSecurity,
  resetSalesOperationSecurity,
  resolveSaleOperationAuthorization,
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  saveSalesOperationSecurity,
} from "./sale/operationSecurity";
export type {
  SaleOperationAuthorization,
  SaleOperationAuthorizationMode,
  SaleOperationCredentials,
  SalesOperationSecurityConfiguration,
  SalesOperationSecurityOperation,
  SalesOperationSecurityUpdate,
} from "./sale/operationSecurity";
export type {
  HardwareBridge,
  CashDrawerPaymentMethod,
  HardwareConfig,
  HardwarePrinter,
  HardwareResult,
  TicketPrintRequest
} from "./hardware/hardware";
export { outputIssuedVoucher } from "./sale/voucherPrinting";
export type { IssuedVoucherPrintSnapshot } from "./sale/voucherPrinting";
export {
  canonicalProductQuantity,
  formatProductQuantity,
  formatQuantityValue,
  isProductQuantityPrecisionValid,
  normalizedProductQuantityType,
  parseProductQuantityInput,
  productQuantityStep,
} from "./sale/productQuantity";
import "./styles/tpv.css";
