import type { TerminalContext } from "../types";

export type ScannerMode = "KEYBOARD";
export type ScannerSubmitKey = "ENTER";
export type TicketPrinterDriver = "WINDOWS_DRIVER" | "ESCPOS_RAW";
export type TicketPrinterConnection = "WINDOWS_PRINTER" | "SERIAL" | "NETWORK";
export type CashDrawerConnection = "NONE" | "PRINTER" | "SERIAL" | "NETWORK";
export type CashDrawerCommandProfile = "ESCPOS_STANDARD";
export type CashDrawerPaymentMethod = "EFECTIVO" | "TARJETA" | "TRANSFERENCIA" | "VALE" | "DESCUENTO" | "OTRO" | "PENDIENTE";
export type CustomerDisplayMode = "COMPACT";
export type PrintableDocumentType = "TICKET" | "INVOICE" | "DELIVERY_NOTE" | "REPORT";
export type PrinterTarget = "TICKET_PRINTER" | "A4_PRINTER";
export type PaperSize = "TICKET_80" | "A4";
export type PrintOrientation = "PORTRAIT" | "LANDSCAPE";
export type ProductLabelDestination = "LABEL_PRINTER" | "TICKET_PRINTER" | "A4";

export type ProductLabelProfile = {
  id: string;
  name: string;
  destination: ProductLabelDestination;
  printerName: string;
  widthMm: number;
  heightMm: number;
  orientation: PrintOrientation;
  marginTopMm: number;
  marginRightMm: number;
  marginBottomMm: number;
  marginLeftMm: number;
  horizontalGapMm: number;
  verticalGapMm: number;
  copies: number;
  showStoreName: boolean;
};

export type DocumentPrintRoute = {
  documentType: PrintableDocumentType;
  printerTarget: PrinterTarget;
  printerName: string;
  paperSize: PaperSize;
  orientation: PrintOrientation;
  copies: number;
  printAutomatically: boolean;
  showPrintDialog: boolean;
};

export type HardwareConfig = {
  scannerMode: ScannerMode;
  scannerSubmitKey: ScannerSubmitKey;
  ticketPrinterDriver: TicketPrinterDriver;
  ticketPrinterConnection: TicketPrinterConnection;
  ticketPrinterName: string;
  openCashDrawerWithTicket: boolean;
  cashDrawerOpeningPaymentMethods: CashDrawerPaymentMethod[];
  cashDrawerConnection: CashDrawerConnection;
  cashDrawerCommandProfile: CashDrawerCommandProfile;
  escposDevicePath: string;
  escposSerialBaudRate: number;
  escposHost: string;
  escposPort: number;
  cashDrawerDevicePath: string;
  cashDrawerSerialBaudRate: number;
  cashDrawerHost: string;
  cashDrawerPort: number;
  customerDisplayEnabled: boolean;
  customerDisplayMode: CustomerDisplayMode;
  customerDisplayIdleLine1: string;
  customerDisplayIdleLine2: string;
  customerDisplayScreenId: string;
  a4PrinterName: string;
  documentPrintRoutes: DocumentPrintRoute[];
  defaultProductLabelProfileId: string;
  productLabelProfiles: ProductLabelProfile[];
};

export type HardwarePrinter = {
  name: string;
  displayName: string;
  isDefault: boolean;
};

export type HardwareErrorCode =
  | "HARDWARE_UNAVAILABLE"
  | "PRINTER_NOT_CONFIGURED"
  | "PRINTER_NOT_FOUND"
  | "PRINT_FAILED"
  | "PDF_EXPORT_FAILED"
  | "WINDOW_UNAVAILABLE"
  | "INVALID_PRINT_REQUEST"
  | "CASH_DRAWER_UNAVAILABLE"
  | "ESCPOS_NOT_AVAILABLE"
  | "CUSTOMER_DISPLAY_UNAVAILABLE"
  | "CUSTOMER_DISPLAY_NOT_OPEN";

export type HardwareResult<T = void> =
  | ({ ok: true } & T)
  | { ok: false; code: HardwareErrorCode; message: string };

export type ExportedFileResult = {
  canceled?: boolean;
  filePath?: string;
};

export type TicketLinePrint = {
  code?: string;
  barcode?: string;
  name: string;
  quantity: number;
  price: number;
  total: number;
  taxesIncluded?: boolean;
  taxPercentage?: number;
  base?: number;
  tax?: number;
  serialNumbers?: string[];
};

export type TicketPaymentPrint = {
  method: string;
  amount: number;
  reference?: string;
};

export type TicketPrintRequest = {
  layout?: "STANDARD" | "GIFT_RECEIPT" | "CANCELLATION_RECEIPT";
  title?: string;
  notice?: string;
  logo?: string;
  documentRaster?: string;
  renderedPdf?: { contentType: "application/pdf"; base64: string };
  notes?: string[];
  details?: Array<{ label: string; value: string }>;
  documentNumber: string;
  storeName: string;
  terminalCode: string;
  issuedAt: string;
  lines: TicketLinePrint[];
  payments: TicketPaymentPrint[];
  subtotal?: number;
  discount?: number;
  tax?: number;
  total: number;
  labels?: { terminal: string; item: string; quantity: string; price: string; total: string; discount?: string; base?: string; tax?: string };
  escposLabels?: { terminal: string; item: string; quantity: string; price: string; total: string; discount?: string; base?: string; tax?: string };
  escposContent?: { storeName: string; terminalCode: string; documentNumber: string; lineNames: string[]; paymentMethods: string[] };
  issuer?: { name: string; taxId: string; address: string; logo?: string };
  customer?: { name: string; taxId: string; address: string };
  partyLabels?: { issuer: string; customer: string; taxId: string };
};

export type A4DocumentPrintRequest = {
  documentType: Exclude<PrintableDocumentType, "TICKET">;
  locale?: "es" | "en" | "zh";
  title: string;
  documentNumber?: string;
  storeName: string;
  terminalCode: string;
  issuedAt: string;
  lines: TicketLinePrint[];
  subtotal: number;
  discount?: number;
  tax: number;
  taxIncluded: boolean | "MIXED";
  total: number;
  logo?: string;
  issuer?: { name: string; taxId: string; phone?: string; logo?: string; address: { line1?: string; postalCode?: string; city?: string; province?: string; country?: string } };
  customer?: { name: string; taxId: string; phone?: string; address: { line1?: string; postalCode?: string; city?: string; province?: string; country?: string } };
  payments?: Array<{ method: string; amount: number; reference?: string }>;
  fiscalProfile?: "IVA" | "IGIC" | "IGIC_MINORISTA";
  bankAccounts?: Array<{ bankName: string; iban: string }>;
  qrUrl?: string;
  qrImage?: string;
  renderedPdf?: { contentType: "application/pdf"; base64: string };
  metadata?: Array<{ label: string; value: string }>;
  notes?: string[];
  labels: {
    terminal: string; description: string; quantity: string; unitPrice: string;
    base: string; tax: string; taxIncluded: string; yes: string; no: string; mixed: string; total: string;
    discount?: string;
    issuer?: string; customer?: string; taxId?: string; phone?: string; notes?: string;
    paymentMethod?: string; bankDetails?: string; bankName?: string; iban?: string;
    taxRate?: string; code?: string; print?: string; close?: string;
  };
};

export type ProductLabelIssuer = {
  name: string;
  taxId: string;
  address: {
    line1?: string;
    postalCode?: string;
    city?: string;
    province?: string;
    country?: string;
  };
};

export type ProductLabelCommercial = {
  badge: string;
  offer?: {
    regularPrice: number;
    offerPrice: number;
    discountPercent: number;
    validUntil?: string;
  };
  promotionLines: string[];
};

export type ProductLabelItem = {
  id: string;
  product: {
    name: string;
    code: string;
    barcode: string;
    price: number;
    commercial?: ProductLabelCommercial;
  };
  copies: number;
};

export type ProductLabelPlacement = {
  instanceId: string;
  itemId: string;
  xMm: number;
  yMm: number;
  widthMm: number;
  heightMm: number;
};

export type ProductLabelPage = {
  placements: ProductLabelPlacement[];
};

export type LegacyProductLabelPrintRequest = {
  storeName: string;
  product: {
    name: string;
    code: string;
    barcode: string;
    price: number;
  };
  profile: ProductLabelProfile;
  copies: number;
  startPosition?: number;
};

export type ProductLabelPrintRequest = LegacyProductLabelPrintRequest | {
  version: 2;
  kind: "SEQUENTIAL" | "A4_LAYOUT";
  storeName: string;
  issuer?: ProductLabelIssuer;
  profile: ProductLabelProfile;
  items: ProductLabelItem[];
  pages?: ProductLabelPage[];
};

export type ScannerTestResult = {
  code: string;
  readAt: string;
};

export type CustomerDisplayScreen = {
  id: string;
  label: string;
  width: number;
  height: number;
  primary: boolean;
};

export type CustomerDisplayState = {
  line1: string;
  line2: string;
};

export type HardwareBridge = {
  listPrinters: () => Promise<HardwareResult<{ printers: HardwarePrinter[] }>>;
  listCustomerDisplays: () => Promise<HardwareResult<{ displays: CustomerDisplayScreen[] }>>;
  getHardwareConfig: () => Promise<HardwareConfig>;
  saveHardwareConfig: (config: HardwareConfig) => Promise<HardwareResult>;
  printTicket: (request: TicketPrintRequest, config?: HardwareConfig) => Promise<HardwareResult>;
  exportTicketPdf: (
    request: TicketPrintRequest,
    defaultFileName: string,
  ) => Promise<HardwareResult<ExportedFileResult>>;
  exportA4DocumentPdf: (
    request: A4DocumentPrintRequest,
    defaultFileName: string,
  ) => Promise<HardwareResult<ExportedFileResult>>;
  printA4Document: (request: A4DocumentPrintRequest, config?: HardwareConfig) => Promise<HardwareResult>;
  printProductLabel: (request: ProductLabelPrintRequest, config?: HardwareConfig) => Promise<HardwareResult>;
  exportProductLabelPdf: (
    request: ProductLabelPrintRequest,
    defaultFileName: string,
  ) => Promise<HardwareResult<ExportedFileResult>>;
  openCashDrawer: (config?: HardwareConfig) => Promise<HardwareResult>;
  testScannerInput: (code: string) => Promise<HardwareResult<ScannerTestResult>>;
  openCustomerDisplay: (config: HardwareConfig, state: CustomerDisplayState) => Promise<HardwareResult>;
  closeCustomerDisplay: () => Promise<HardwareResult>;
  updateCustomerDisplay: (state: CustomerDisplayState) => Promise<HardwareResult>;
};

export const defaultHardwareConfig: HardwareConfig = {
  scannerMode: "KEYBOARD",
  scannerSubmitKey: "ENTER",
  ticketPrinterDriver: "WINDOWS_DRIVER",
  ticketPrinterConnection: "WINDOWS_PRINTER",
  ticketPrinterName: "",
  openCashDrawerWithTicket: true,
  cashDrawerOpeningPaymentMethods: ["EFECTIVO"],
  cashDrawerConnection: "PRINTER",
  cashDrawerCommandProfile: "ESCPOS_STANDARD",
  escposDevicePath: "",
  escposSerialBaudRate: 9600,
  escposHost: "",
  escposPort: 9100,
  cashDrawerDevicePath: "",
  cashDrawerSerialBaudRate: 9600,
  cashDrawerHost: "",
  cashDrawerPort: 9100,
  customerDisplayEnabled: false,
  customerDisplayMode: "COMPACT",
  customerDisplayIdleLine1: "BIENVENIDO",
  customerDisplayIdleLine2: "GRACIAS POR SU COMPRA",
  customerDisplayScreenId: "",
  a4PrinterName: "",
  documentPrintRoutes: [
    {
      documentType: "TICKET",
      printerTarget: "TICKET_PRINTER",
      printerName: "",
      paperSize: "TICKET_80",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: true,
      showPrintDialog: false
    },
    {
      documentType: "INVOICE",
      printerTarget: "A4_PRINTER",
      printerName: "",
      paperSize: "A4",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: false,
      showPrintDialog: true
    },
    {
      documentType: "DELIVERY_NOTE",
      printerTarget: "A4_PRINTER",
      printerName: "",
      paperSize: "A4",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: false,
      showPrintDialog: true
    },
    {
      documentType: "REPORT",
      printerTarget: "A4_PRINTER",
      printerName: "",
      paperSize: "A4",
      orientation: "PORTRAIT",
      copies: 1,
      printAutomatically: false,
      showPrintDialog: true
    }
  ],
  defaultProductLabelProfileId: "ticket-58x40",
  productLabelProfiles: [{
    id: "ticket-58x40",
    name: "Ticket 58 x 40 mm",
    destination: "TICKET_PRINTER",
    printerName: "",
    widthMm: 58,
    heightMm: 40,
    orientation: "PORTRAIT",
    marginTopMm: 5,
    marginRightMm: 5,
    marginBottomMm: 5,
    marginLeftMm: 5,
    horizontalGapMm: 2,
    verticalGapMm: 2,
    copies: 1,
    showStoreName: true,
  }]
};

export function normalizeHardwareConfigForUi(
  config?: Partial<HardwareConfig> | null,
): HardwareConfig {
  const configuredProfiles = Array.isArray(config?.productLabelProfiles)
    ? config.productLabelProfiles.filter((profile): profile is ProductLabelProfile =>
        Boolean(profile && typeof profile.id === "string" && profile.id.trim()))
    : [];
  const productLabelProfiles = (configuredProfiles.length > 0
    ? configuredProfiles
    : defaultHardwareConfig.productLabelProfiles
  ).map((profile) => ({
    ...defaultHardwareConfig.productLabelProfiles[0],
    ...profile,
    id: String(profile.id),
    name: String(profile.name || profile.id),
  }));
  const requestedDefault = String(config?.defaultProductLabelProfileId ?? "");
  const defaultProductLabelProfileId = productLabelProfiles.some(
    (profile) => profile.id === requestedDefault,
  )
    ? requestedDefault
    : productLabelProfiles[0].id;

  return {
    ...defaultHardwareConfig,
    ...config,
    defaultProductLabelProfileId,
    productLabelProfiles,
  };
}

export function createHardwareUnavailableResult<T = void>(message = "Hardware local no disponible"): HardwareResult<T> {
  return { ok: false, code: "HARDWARE_UNAVAILABLE", message };
}

export function createTestTicket(terminalContext: Pick<TerminalContext, "storeName" | "terminalCode">): TicketPrintRequest {
  const lines: TicketLinePrint[] = [];
  const total = lines.reduce((sum, line) => sum + line.total, 0);

  return {
    documentNumber: `TEST-${terminalContext.terminalCode}`,
    storeName: terminalContext.storeName,
    terminalCode: terminalContext.terminalCode,
    issuedAt: new Date().toISOString(),
    lines,
    payments: [],
    total
  };
}

export function createA4TestDocument(terminalContext: Pick<TerminalContext, "storeName" | "terminalCode">): A4DocumentPrintRequest {
  const lines: TicketLinePrint[] = [];
  const total = lines.reduce((sum, line) => sum + line.total, 0);

  return {
    documentType: "REPORT",
    title: "Prueba A4",
    storeName: terminalContext.storeName,
    terminalCode: terminalContext.terminalCode,
    issuedAt: new Date().toISOString(),
    lines,
    subtotal: total,
    tax: 0,
    taxIncluded: true,
    total,
    labels: { terminal: "Terminal", description: "Description", quantity: "Quantity",
      unitPrice: "Unit price", base: "Base", tax: "Tax", taxIncluded: "Tax included",
      yes: "Yes", no: "No", mixed: "Mixed", total: "Total" }
  };
}

function money(value: number) {
  return Number(value || 0).toFixed(2);
}

export function createCustomerDisplayIdleState(line1: string, line2: string): CustomerDisplayState {
  return { line1, line2 };
}

export function createCustomerDisplaySaleState(item: { name: string; quantity: number; price: number }): CustomerDisplayState {
  return {
    line1: item.name,
    line2: `${item.quantity} x ${money(item.price)}`
  };
}

export function createCustomerDisplayPaymentState(payment: { total: number; change?: number }): CustomerDisplayState {
  return {
    line1: `TOTAL: ${money(payment.total)}`,
    line2: typeof payment.change === "number" ? `CAMBIO: ${money(payment.change)}` : ""
  };
}

const browserFallbackBridge: HardwareBridge = {
  listPrinters: async () => createHardwareUnavailableResult(),
  listCustomerDisplays: async () => createHardwareUnavailableResult(),
  getHardwareConfig: async () => defaultHardwareConfig,
  saveHardwareConfig: async () => createHardwareUnavailableResult(),
  printTicket: async () => createHardwareUnavailableResult(),
  exportTicketPdf: async () => createHardwareUnavailableResult(),
  exportA4DocumentPdf: async () => createHardwareUnavailableResult(),
  printA4Document: async () => createHardwareUnavailableResult(),
  printProductLabel: async () => createHardwareUnavailableResult(),
  exportProductLabelPdf: async () => createHardwareUnavailableResult(),
  openCashDrawer: async () => createHardwareUnavailableResult(),
  testScannerInput: async (code) => ({
    ok: true,
    code,
    readAt: new Date().toISOString()
  }),
  openCustomerDisplay: async () => createHardwareUnavailableResult(),
  closeCustomerDisplay: async () => createHardwareUnavailableResult(),
  updateCustomerDisplay: async () => createHardwareUnavailableResult()
};

export function getHardwareBridge(): HardwareBridge {
  if (typeof window === "undefined") {
    return browserFallbackBridge;
  }

  return window.tpvDesktop?.hardware ?? browserFallbackBridge;
}
