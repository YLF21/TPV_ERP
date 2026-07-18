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
  | "CASH_DRAWER_UNAVAILABLE"
  | "ESCPOS_NOT_AVAILABLE"
  | "CUSTOMER_DISPLAY_UNAVAILABLE"
  | "CUSTOMER_DISPLAY_NOT_OPEN";

export type HardwareResult<T = void> =
  | ({ ok: true } & T)
  | { ok: false; code: HardwareErrorCode; message: string };

export type TicketLinePrint = {
  name: string;
  quantity: number;
  price: number;
  total: number;
  taxesIncluded?: boolean;
};

export type TicketPaymentPrint = {
  method: string;
  amount: number;
};

export type TicketPrintRequest = {
  documentNumber: string;
  storeName: string;
  terminalCode: string;
  issuedAt: string;
  lines: TicketLinePrint[];
  payments: TicketPaymentPrint[];
  subtotal?: number;
  tax?: number;
  total: number;
  labels?: { terminal: string; item: string; quantity: string; price: string; total: string };
  escposLabels?: { terminal: string; item: string; quantity: string; price: string; total: string; base?: string; tax?: string };
  escposContent?: { storeName: string; terminalCode: string; documentNumber: string; lineNames: string[]; paymentMethods: string[] };
  issuer?: { name: string; taxId: string; address: string };
  customer?: { name: string; taxId: string; address: string };
  partyLabels?: { issuer: string; customer: string; taxId: string };
};

export type A4DocumentPrintRequest = {
  documentType: Exclude<PrintableDocumentType, "TICKET">;
  title: string;
  storeName: string;
  terminalCode: string;
  issuedAt: string;
  lines: TicketLinePrint[];
  subtotal: number;
  tax: number;
  taxIncluded: boolean | "MIXED";
  total: number;
  issuer?: { name: string; taxId: string; address: { line1?: string; postalCode?: string; city?: string; province?: string; country?: string } };
  customer?: { name: string; taxId: string; address: { line1?: string; postalCode?: string; city?: string; province?: string; country?: string } };
  labels: {
    terminal: string; description: string; quantity: string; unitPrice: string;
    base: string; tax: string; taxIncluded: string; yes: string; no: string; mixed: string; total: string;
    issuer?: string; customer?: string; taxId?: string;
  };
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
  printA4Document: (request: A4DocumentPrintRequest, config?: HardwareConfig) => Promise<HardwareResult>;
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
  ]
};

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
  printA4Document: async () => createHardwareUnavailableResult(),
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
