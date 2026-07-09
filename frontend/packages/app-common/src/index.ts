export { authenticate, authenticateRemote, canAccessApp, hasPermission } from "./auth/auth";
export { apiBaseUrl, devTerminalContext } from "./api/runtime";
export { AppFrame } from "./components/AppFrame";
export { HardwareSettingsScreen } from "./components/HardwareSettingsScreen";
export { LoginScreen } from "./components/LoginScreen";
export { PromotionListScreen } from "./components/PromotionListScreen";
export { PromotionPreviewPanel } from "./components/PromotionPreviewPanel";
export { PromotionWizard } from "./components/PromotionWizard";
export { SalesReportScreen } from "./components/SalesReportScreen";
export { SaleScreen } from "./components/SaleScreen";
export { SettingsScreen } from "./components/SettingsScreen";
export { SessionHomeScreen } from "./components/SessionHomeScreen";
export { StockScreen } from "./components/StockScreen";
export {
  createHardwareUnavailableResult,
  createTestTicket,
  defaultHardwareConfig,
  getHardwareBridge
} from "./hardware/hardware";
export { createTranslator, LocalizedMessages, messages } from "./i18n/LocalizedMessages";
export type { AppKind, LocaleCode, Permission, TerminalContext, UserSession } from "./types";
export type {
  PromotionPreview,
  PromotionPreviewAppliedPromotion,
  PromotionPreviewGeneratedCoupon,
  PromotionPreviewUsedCoupon
} from "./components/PromotionPreviewPanel";
export type {
  PromotionCustomerSegment,
  PromotionDraft,
  PromotionRequest,
  PromotionScope,
  PromotionStatus,
  PromotionType,
  PromotionView
} from "./components/PromotionWizard";
export type {
  HardwareBridge,
  CashDrawerPaymentMethod,
  HardwareConfig,
  HardwarePrinter,
  HardwareResult,
  TicketPrintRequest
} from "./hardware/hardware";
import "./styles/tpv.css";
