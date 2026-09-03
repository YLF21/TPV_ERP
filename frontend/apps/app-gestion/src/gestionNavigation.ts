import type { Icon } from "@phosphor-icons/react";
import {
  ArrowCircleDown,
  ArrowCircleUp,
  ArrowsLeftRight,
  Barcode,
  BellRinging,
  Briefcase,
  CalendarX,
  CashRegister,
  ChartLineUp,
  Certificate,
  ClipboardText,
  CreditCard,
  CubeFocus,
  CurrencyEur,
  Devices,
  Files,
  FileArrowDown,
  FileArrowUp,
  FilePlus,
  FileText,
  Gauge,
  GearSix,
  IdentificationBadge,
  IdentificationCard,
  Invoice,
  Key,
  LockKey,
  ListChecks,
  Medal,
  Note as NoteIcon,
  Package,
  PencilSimpleLine,
  Percent,
  PlusMinus,
  Printer,
  Prohibit,
  Receipt,
  SealPercent,
  ShieldCheck,
  ShieldChevron,
  SlidersHorizontal,
  Sparkle,
  Stack,
  Storefront,
  Tag,
  Ticket,
  TreeStructure,
  Trophy,
  UserCircleGear,
  UserGear,
  UserRectangle,
  UsersThree,
  Vault,
  Warehouse,
} from "@phosphor-icons/react";

export type GestionGroupLock = "FISCAL" | "SEGURIDAD" | "CONFIGURACION";

export type GestionNavigationDestination = {
  key: string;
  labelKey: string;
  icon: Icon;
  lock?: GestionGroupLock;
};

export type GestionNavigationGroup = {
  key: string;
  labelKey: string;
  icon: Icon;
  destinations: GestionNavigationDestination[];
  direct?: boolean;
  lock?: GestionGroupLock;
};

const destination = (key: string, labelKey: string, icon: Icon, lock?: GestionGroupLock): GestionNavigationDestination => ({
  key,
  labelKey,
  icon,
  ...(lock ? { lock } : {}),
});

/**
 * Stable APP GESTIÓN information architecture. Handlers and permissions stay
 * outside this registry; this file is the single source for order, labels and
 * destination icons.
 */
export const gestionNavigationGroups: GestionNavigationGroup[] = [
  {
    key: "overview",
    labelKey: "gestion.navigation.group.overview",
    icon: Gauge,
    direct: true,
    destinations: [destination("dashboard", "gestion.navigation.destination.summary", Gauge)],
  },
  {
    key: "control-alerts",
    labelKey: "gestion.navigation.group.controlAlerts",
    icon: BellRinging,
    direct: true,
    destinations: [destination("controlAlerts", "gestion.navigation.destination.controlAlerts", BellRinging)],
  },
  {
    key: "cash",
    labelKey: "gestion.navigation.group.cash",
    icon: CashRegister,
    destinations: [
      destination("salesReport.dailySales", "gestion.navigation.destination.dailySales", ChartLineUp),
      destination("cashCurrentBalances", "gestion.navigation.destination.cashInDrawer", CurrencyEur),
      destination("cashClosures", "gestion.navigation.destination.cashClosures", Vault),
    ],
  },
  {
    key: "customer-documents",
    labelKey: "gestion.navigation.group.customerDocuments",
    icon: FileArrowUp,
    destinations: [
      destination("salesReport.salesDocuments", "gestion.navigation.destination.salesDocuments", Files),
      destination("salesReport.tickets", "gestion.navigation.destination.tickets", Receipt),
      destination("salesReport.deliveryNotes", "gestion.navigation.destination.salesDeliveryNotes", NoteIcon),
      destination("salesReport.invoices", "gestion.navigation.destination.salesInvoices", Invoice),
      destination("vouchers", "gestion.navigation.destination.issuedVouchers", Ticket),
    ],
  },
  {
    key: "supplier-documents",
    labelKey: "gestion.navigation.group.supplierDocuments",
    icon: FileArrowDown,
    destinations: [
      destination("stock.warehouse.purchaseDeliveryNotes", "gestion.navigation.destination.managePurchaseDeliveryNotes", FilePlus),
      destination("stock.warehouse.purchaseInvoices", "gestion.navigation.destination.managePurchaseInvoices", Invoice),
      destination("salesReport.inputDeliveryNotes", "gestion.navigation.destination.purchaseDeliveryNoteReport", ClipboardText),
      destination("salesReport.inputInvoices", "gestion.navigation.destination.purchaseInvoiceReport", FileText),
      destination("stock.warehouse.goodsCheck", "gestion.navigation.destination.orderCheck", ListChecks),
    ],
  },
  {
    key: "products",
    labelKey: "gestion.navigation.group.products",
    icon: Package,
    destinations: [
      destination("stock.current", "gestion.navigation.destination.stock", Package),
      destination("stock.topSales", "gestion.navigation.destination.topSales", Trophy),
      destination("stock.offers", "gestion.navigation.destination.productsOnOffer", Tag),
      destination("stock.memberPrice", "gestion.navigation.destination.memberPriceProducts", IdentificationBadge),
      destination("stock.promotions", "gestion.navigation.destination.promotedProducts", Sparkle),
      destination("stock.noDiscount", "gestion.navigation.destination.noDiscountProducts", Prohibit),
      destination("stock.bulkEdit", "gestion.navigation.destination.bulkProductEdit", PencilSimpleLine),
      destination("promotions", "gestion.navigation.destination.promotions", SealPercent),
      destination("families", "gestion.navigation.destination.families", TreeStructure),
    ],
  },
  {
    key: "warehouse",
    labelKey: "gestion.navigation.group.warehouse",
    icon: Warehouse,
    destinations: [
      destination("stock.settings.configuration", "gestion.navigation.destination.stockSettings", SlidersHorizontal),
      destination("stock.warehouse.management", "gestion.navigation.destination.warehouses", Warehouse),
      destination("stock.warehouse.transfer", "gestion.navigation.destination.transfers", ArrowsLeftRight),
      destination("stock.warehouse.adjustment", "gestion.navigation.destination.stockAdjustments", PlusMinus),
      destination("stock.warehouse.count", "gestion.navigation.destination.physicalCounts", ClipboardText),
      destination("stock.warehouse.input", "gestion.navigation.destination.warehouseInputOperation", ArrowCircleDown),
      destination("stock.warehouse.output", "gestion.navigation.destination.warehouseOutputOperation", ArrowCircleUp),
      destination("salesReport.inputWarehouse", "gestion.navigation.destination.warehouseInputReport", FileArrowDown),
      destination("salesReport.warehouseOutputs", "gestion.navigation.destination.warehouseOutputReport", FileArrowUp),
    ],
  },
  {
    key: "third-parties",
    labelKey: "gestion.navigation.group.thirdParties",
    icon: UsersThree,
    destinations: [
      destination("stock.party.customers", "gestion.navigation.destination.customers", UserRectangle),
      destination("stock.party.suppliers", "gestion.navigation.destination.suppliers", Storefront),
      destination("stock.party.members", "gestion.navigation.destination.members", IdentificationCard),
      destination("memberCategories", "gestion.navigation.destination.memberCategories", Stack),
    ],
  },
  {
    key: "fiscal",
    labelKey: "gestion.navigation.group.fiscal",
    icon: ShieldCheck,
    direct: true,
    destinations: [destination("verifactu", "gestion.navigation.destination.fiscal", ShieldCheck, "FISCAL")],
  },
  {
    key: "security",
    labelKey: "gestion.navigation.group.security",
    icon: LockKey,
    lock: "SEGURIDAD",
    destinations: [
      destination("users", "gestion.navigation.destination.users", UserGear, "SEGURIDAD"),
      destination("roles", "gestion.navigation.destination.roles", Key, "SEGURIDAD"),
      destination("terminals", "gestion.navigation.destination.terminals", Devices, "SEGURIDAD"),
      destination("salesOperationSecurity", "gestion.navigation.destination.salesSecurity", ShieldChevron, "SEGURIDAD"),
    ],
  },
  {
    key: "configuration",
    labelKey: "gestion.navigation.group.configuration",
    icon: GearSix,
    lock: "CONFIGURACION",
    destinations: [
      destination("paymentMethods", "gestion.navigation.destination.paymentMethods", CreditCard, "CONFIGURACION"),
      destination("taxes", "gestion.navigation.destination.taxes", Percent, "CONFIGURACION"),
      destination("memberLoyaltySettings", "gestion.navigation.destination.memberLoyalty", Medal, "CONFIGURACION"),
      destination("internalEan", "gestion.navigation.destination.internalEan", Barcode, "CONFIGURACION"),
      destination("documentPrintSettings", "gestion.navigation.destination.printedDocuments", Printer, "CONFIGURACION"),
      destination("documentTemplates", "gestion.navigation.destination.documentTemplates", Files, "CONFIGURACION"),
      destination("voucherSettings", "gestion.navigation.destination.voucherExpiry", CalendarX, "CONFIGURACION"),
      destination("licenses", "gestion.navigation.destination.licenses", Certificate, "CONFIGURACION"),
      destination("productManagement", "gestion.navigation.destination.productManagement", CubeFocus, "CONFIGURACION"),
      destination("customerManagement", "gestion.navigation.destination.customerManagement", UserCircleGear, "CONFIGURACION"),
      destination("supplierManagement", "gestion.navigation.destination.supplierManagement", Briefcase, "CONFIGURACION"),
    ],
  },
];

export const gestionNavigationDestinationCount = gestionNavigationGroups
  .reduce((count, group) => count + group.destinations.length, 0);

export const gestionNavigationGroupCount = gestionNavigationGroups.length;

export function findGestionNavigationDestination(key: string) {
  return gestionNavigationGroups.flatMap((group) => group.destinations).find((item) => item.key === key);
}
