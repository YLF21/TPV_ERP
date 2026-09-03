import { lazy, StrictMode, Suspense, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { useMemo } from "react";
import {
  AppFrame,
  ApiError,
  apiRequest,
  LoginScreen,
  PromotionListScreen,
  createTranslator,
  devTerminalContext,
  gestionGroupLockedEvent,
  loadTerminalIdentity,
  userCanManageWarehouses,
  visibleSalesReports,
  visibleStockViewsForSession,
  visibleWarehouseSectionsForSession,
  type LocaleCode,
  type PartyDirectoryKind,
  type StockViewKey,
  type TerminalContext,
  type UserSession,
  type WarehouseSection
} from "@tpverp/app-common";
import "../../../packages/app-common/src/styles/tpv.css";
import "./gestion.css";
import { canManageFamilies, canManageTaxes, visibleGestionModules } from "./gestionAccess";
import { GestionDashboard } from "./GestionDashboard";
import { ControlAlertsScreen } from "./ControlAlertsScreen";
import { ServerTerminalSetupScreen } from "./ServerTerminalSetupScreen";
import { GestionShell, type GestionNavigationItem } from "./GestionShell";
import { MemberCategoriesScreen } from "./MemberCategoriesScreen";
import { FamiliesScreen } from "./FamiliesScreen";
import {
  gestionNavigationGroups,
  type GestionGroupLock,
} from "./gestionNavigation";
import { GestionGroupUnlockDialog } from "./GestionGroupUnlockDialog";

const StockScreen = lazy(() =>
  import("../../../packages/app-common/src/components/StockScreen").then(({ StockScreen }) => ({
    default: StockScreen
  }))
);

const SalesReportScreen = lazy(() =>
  import("../../../packages/app-common/src/components/SalesReportScreen").then(({ SalesReportScreen }) => ({
    default: SalesReportScreen
  }))
);

const SupplierManagement = lazy(() =>
  import("./SupplierManagementScreen").then(({ SupplierManagementScreen }) => ({
    default: SupplierManagementScreen
  }))
);

const PaymentMethodSettingsScreen = lazy(() =>
  import("./PaymentMethodSettingsScreen").then(({ PaymentMethodSettingsScreen }) => ({
    default: PaymentMethodSettingsScreen
  }))
);

const TaxSettingsScreen = lazy(() =>
  import("./TaxSettingsScreen").then(({ TaxSettingsScreen }) => ({ default: TaxSettingsScreen }))
);

const SalesOperationSecurityScreen = lazy(() =>
  import("./SalesOperationSecurityScreen").then(({ SalesOperationSecurityScreen }) => ({
    default: SalesOperationSecurityScreen
  }))
);

const MemberLoyaltySettingsScreen = lazy(() =>
  import("./MemberLoyaltySettingsScreen").then(({ MemberLoyaltySettingsScreen }) => ({
    default: MemberLoyaltySettingsScreen
  }))
);

const InternalEanSettingsScreen = lazy(() =>
  import("./InternalEanSettingsScreen").then(({ InternalEanSettingsScreen }) => ({
    default: InternalEanSettingsScreen
  }))
);

const SecurityAdministrationScreen = lazy(() =>
  import("./SecurityAdministrationScreen").then(({ SecurityAdministrationScreen }) => ({
    default: SecurityAdministrationScreen
  }))
);

const TerminalManagementScreen = lazy(() =>
  import("./TerminalManagementScreen").then(({ TerminalManagementScreen }) => ({
    default: TerminalManagementScreen
  }))
);

const WarehouseScreen = lazy(() =>
  import("../../../packages/app-common/src/components/WarehouseScreen").then(({ WarehouseScreen }) => ({
    default: WarehouseScreen
  }))
);

const WarehouseManagementScreen = lazy(() =>
  import("./WarehouseManagementScreen").then(({ WarehouseManagementScreen }) => ({
    default: WarehouseManagementScreen
  }))
);

const WarehouseOperationsScreen = lazy(() =>
  import("./WarehouseOperationsScreen").then(({ WarehouseOperationsScreen }) => ({
    default: WarehouseOperationsScreen
  }))
);

const VerifactuManagementScreen = lazy(() =>
  import("./VerifactuManagementScreen").then(({ VerifactuManagementScreen }) => ({
    default: VerifactuManagementScreen
  }))
);

const CashClosuresScreen = lazy(() =>
  import("./CashClosuresScreen").then(({ CashClosuresScreen }) => ({
    default: CashClosuresScreen
  }))
);

const CashCurrentBalancesScreen = lazy(() =>
  import("./CashCurrentBalancesScreen").then(({ CashCurrentBalancesScreen }) => ({
    default: CashCurrentBalancesScreen
  }))
);

const DocumentTemplateSettingsScreen = lazy(() =>
  import("./DocumentTemplateSettingsScreen").then(({ DocumentTemplateSettingsScreen }) => ({
    default: DocumentTemplateSettingsScreen
  }))
);

const StoreDocumentPrintSettingsScreen = lazy(() =>
  import("./StoreDocumentPrintSettingsScreen").then(({ StoreDocumentPrintSettingsScreen }) => ({
    default: StoreDocumentPrintSettingsScreen
  }))
);

const VoucherManagementScreen = lazy(() =>
  import("./VoucherManagementScreen").then(({ VoucherManagementScreen }) => ({
    default: VoucherManagementScreen
  }))
);

const VoucherSettingsScreen = lazy(() =>
  import("./VoucherSettingsScreen").then(({ VoucherSettingsScreen }) => ({
    default: VoucherSettingsScreen
  }))
);

const LicenseSaasManagementScreen = lazy(() =>
  import("./LicenseSaasManagementScreen").then(({ LicenseSaasManagementScreen }) => ({
    default: LicenseSaasManagementScreen
  }))
);

type GestionModule = "dashboard" | "verifactu" | "controlAlerts" | "cashClosures" | "cashCurrentBalances" | "promotions" | "sales" | "vouchers" | "stock" | "users" | "roles" | "terminals" | "paymentMethods" | "taxes" | "salesOperationSecurity" | "memberLoyaltySettings" | "memberCategories" | "internalEan" | "documentTemplates" | "documentPrintSettings" | "voucherSettings" | "licenses" | "families" | "productManagement" | "customerManagement" | "supplierManagement";
type StockSelection = {
  key: string;
  view?: StockViewKey;
  partyDirectory?: PartyDirectoryKind;
  settingsMode?: "configuration";
  warehouseSection?: WarehouseSection;
  warehouseManagement?: boolean;
  warehouseOperation?: import("./WarehouseOperationsScreen").WarehouseOperationMode;
};

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [session, setSession] = useState<UserSession | null>(null);
  const [module, setModule] = useState<GestionModule>("dashboard");
  const [salesReport, setSalesReport] = useState("salesReport.dailySales");
  const [stockSelection, setStockSelection] = useState<StockSelection>({ key: "stock.current", view: "stock.current" });
  const [terminalContext, setTerminalContext] = useState<TerminalContext | null | undefined>(undefined);
  const [logoutError, setLogoutError] = useState(false);
  const [logoutBusy, setLogoutBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    async function loadIdentity() {
      const identity = await loadTerminalIdentity(
        window.tpvDesktop?.terminalIdentity,
        import.meta.env.DEV ? devTerminalContext : null
      );
      if (!cancelled) setTerminalContext(identity);
    }
    void loadIdentity();
    return () => { cancelled = true; };
  }, []);

  if (terminalContext === undefined) {
    return null;
  }

  if (terminalContext === null) {
    return <ServerTerminalSetupScreen locale={locale} onProvisioned={setTerminalContext} />;
  }

  if (!session) {
    return (
      <LoginScreen
        app="gestion"
        locale={locale}
        terminalContext={terminalContext}
        onLocaleChange={setLocale}
        onLogin={(nextSession) => {
          setModule("dashboard");
          setSession(nextSession);
        }}
      />
    );
  }

  const activeSession = session;

  async function handleLogout() {
    if (logoutBusy) return;
    setLogoutBusy(true);
    setLogoutError(false);
    try {
      await apiRequest<void>("/auth/logout", { method: "POST", token: activeSession.accessToken });
      setModule("dashboard");
      setSession(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setModule("dashboard");
        setSession(null);
      } else {
        setLogoutError(true);
      }
    } finally {
      setLogoutBusy(false);
    }
  }

  return (
    <AppFrame
      titleKey="gestion.title"
      locale={locale}
      session={session}
      onLocaleChange={setLocale}
      onLogout={handleLogout}
      logoutError={logoutError ? "gestion.logoutError" : undefined}
      logoutBusy={logoutBusy}
    >
      <GestionScreen
        locale={locale}
        session={activeSession}
        terminalContext={terminalContext}
        module={module}
        salesReport={salesReport}
        stockSelection={stockSelection}
        onOpenDashboard={() => setModule("dashboard")}
        onOpenVerifactu={() => setModule("verifactu")}
        onOpenControlAlerts={() => setModule("controlAlerts")}
        onOpenCashClosures={() => setModule("cashClosures")}
        onOpenCashCurrentBalances={() => setModule("cashCurrentBalances")}
        onOpenSales={(report) => {
          setSalesReport(report);
          setModule("sales");
        }}
        onOpenVouchers={() => setModule("vouchers")}
        onOpenPromotions={() => setModule("promotions")}
        onOpenUsers={() => setModule("users")}
        onOpenTerminals={() => setModule("terminals")}
        onOpenRoles={() => setModule("roles")}
        onOpenPaymentMethods={() => setModule("paymentMethods")}
        onOpenTaxes={() => setModule("taxes")}
        onOpenSalesOperationSecurity={() => setModule("salesOperationSecurity")}
        onOpenMemberLoyaltySettings={() => setModule("memberLoyaltySettings")}
        onOpenMemberCategories={() => setModule("memberCategories")}
        onOpenInternalEan={() => setModule("internalEan")}
        onOpenDocumentTemplates={() => setModule("documentTemplates")}
        onOpenDocumentPrintSettings={() => setModule("documentPrintSettings")}
        onOpenVoucherSettings={() => setModule("voucherSettings")}
        onOpenLicenses={() => setModule("licenses")}
        onOpenFamilies={() => setModule("families")}
        onOpenProductManagement={() => setModule("productManagement")}
        onOpenCustomerManagement={() => setModule("customerManagement")}
        onOpenSupplierManagement={() => setModule("supplierManagement")}
        onOpenStock={(selection) => {
          setStockSelection(selection);
          setModule("stock");
        }}
        onLocaleChange={setLocale}
        onLogout={() => { void handleLogout(); }}
      />
    </AppFrame>
  );
}

function GestionScreen({
  locale,
  session,
  terminalContext,
  module,
  salesReport,
  stockSelection,
  onOpenDashboard,
  onOpenVerifactu,
  onOpenControlAlerts,
  onOpenCashClosures,
  onOpenCashCurrentBalances,
  onOpenSales,
  onOpenVouchers,
  onOpenPromotions,
  onOpenUsers,
  onOpenTerminals,
  onOpenRoles,
  onOpenPaymentMethods,
  onOpenTaxes,
  onOpenSalesOperationSecurity,
  onOpenMemberLoyaltySettings,
  onOpenMemberCategories,
  onOpenInternalEan,
  onOpenDocumentTemplates,
  onOpenDocumentPrintSettings,
  onOpenVoucherSettings,
  onOpenLicenses,
  onOpenFamilies,
  onOpenProductManagement,
  onOpenCustomerManagement,
  onOpenSupplierManagement,
  onOpenStock,
  onLocaleChange,
  onLogout
}: {
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  module: GestionModule;
  salesReport: string;
  stockSelection: StockSelection;
  onOpenDashboard: () => void;
  onOpenVerifactu: () => void;
  onOpenControlAlerts: () => void;
  onOpenCashClosures: () => void;
  onOpenCashCurrentBalances: () => void;
  onOpenSales: (report: string) => void;
  onOpenVouchers: () => void;
  onOpenPromotions: () => void;
  onOpenUsers: () => void;
  onOpenTerminals: () => void;
  onOpenRoles: () => void;
  onOpenPaymentMethods: () => void;
  onOpenTaxes: () => void;
  onOpenSalesOperationSecurity: () => void;
  onOpenMemberLoyaltySettings: () => void;
  onOpenMemberCategories: () => void;
  onOpenInternalEan: () => void;
  onOpenDocumentTemplates: () => void;
  onOpenDocumentPrintSettings: () => void;
  onOpenVoucherSettings: () => void;
  onOpenLicenses: () => void;
  onOpenFamilies: () => void;
  onOpenProductManagement: () => void;
  onOpenCustomerManagement: () => void;
  onOpenSupplierManagement: () => void;
  onOpenStock: (selection: StockSelection) => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout: () => void;
}) {
  const t = useMemo(() => createTranslator(locale), [locale]);
  const modules = visibleGestionModules(session);
  const verifactuAllowed = modules.includes("gestion.verifactu");
  const canConfigurePaymentMethods = session.permissions.includes("ADMIN");
  const canManageTaxesForSession = canManageTaxes(session);
  const canReadLicenses = canConfigurePaymentMethods || session.permissions.includes("LICENSES_MANAGE");
  const canManageDocumentTemplates = modules.includes("gestion.documentTemplates");
  const canManageProducts = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_PRODUCTO");
  const canReadCustomers = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_CLIENTE_PROVEEDOR")
    || session.permissions.includes("CUSTOMERS_READ");
  const canReadSuppliers = session.permissions.includes("ADMIN")
    || session.permissions.includes("GESTION_CLIENTE_PROVEEDOR")
    || session.permissions.includes("GESTION_ALMACEN")
    || session.permissions.includes("SUPPLIERS_READ");
  const effectiveModule = (module === "verifactu" && !verifactuAllowed)
    || ((module === "paymentMethods" || module === "salesOperationSecurity" || module === "memberLoyaltySettings" || module === "memberCategories" || module === "internalEan" || module === "documentPrintSettings" || module === "voucherSettings")
      && !canConfigurePaymentMethods)
    || (module === "taxes" && !canManageTaxesForSession)
    || (module === "licenses" && !canReadLicenses)
    || (module === "documentTemplates" && !canManageDocumentTemplates)
    || (module === "vouchers" && !modules.includes("gestion.sales"))
    || (module === "families" && !canManageFamilies(session))
    || ((module === "productManagement" || module === "customerManagement" || module === "supplierManagement")
      && !canConfigurePaymentMethods)
    ? "dashboard"
    : module;
  const canManageFamiliesForSession = canManageFamilies(session);
  const reports = visibleSalesReports(session).all;
  const stockViews = visibleStockViewsForSession(session);
  const warehouseSections = visibleWarehouseSectionsForSession(session);
  const [unlockedGroups, setUnlockedGroups] = useState<Set<GestionGroupLock>>(() => new Set());
  const [unlockRequest, setUnlockRequest] = useState<{
    group: GestionGroupLock;
    resolve: (allowed: boolean) => void;
  } | null>(null);
  const stockChildren: GestionNavigationItem[] = [
    ...stockViews.map((view) => ({
      key: view,
      label: t(view),
      onOpen: () => onOpenStock({ key: view, view })
    })),
    ...(userCanManageWarehouses(session) ? [{
      key: "stock.settings.configuration",
      label: t("stock.settings.configuration"),
            onOpen: () => onOpenStock({ key: "stock.settings.configuration", settingsMode: "configuration" as const })
    }] : [])
  ];
  const warehouseChildren: GestionNavigationItem[] = [
    ...(userCanManageWarehouses(session) ? [{
      key: "stock.warehouse.management",
      label: t("warehouse.management.navigation"),
      onOpen: () => onOpenStock({
        key: "stock.warehouse.management",
        warehouseManagement: true
      })
    }] : []),
    ...((session.permissions.includes("ADMIN")
      || session.permissions.includes("GESTION_ALMACEN")
      || session.permissions.includes("STOCK_TRANSFER")) ? [{
      key: "stock.warehouse.transfer",
      label: t("warehouse.transfer.navigation"),
      onOpen: () => onOpenStock({ key: "stock.warehouse.transfer", warehouseOperation: "transfer" })
    }] : []),
    ...((session.permissions.includes("ADMIN")
      || session.permissions.includes("GESTION_ALMACEN")
      || session.permissions.includes("STOCK_ADJUST")) ? [{
      key: "stock.warehouse.adjustment",
      label: t("warehouse.adjustment.navigation"),
      onOpen: () => onOpenStock({ key: "stock.warehouse.adjustment", warehouseOperation: "adjustment" })
    }] : []),
    ...((session.permissions.includes("ADMIN") || session.permissions.includes("GESTION_ALMACEN")) ? [{
      key: "stock.warehouse.count",
      label: t("warehouse.count.navigation"),
      onOpen: () => onOpenStock({ key: "stock.warehouse.count", warehouseOperation: "count" })
    }] : []),
    ...warehouseSections.map((warehouseSection) => ({
      key: `stock.warehouse.${warehouseSection}`,
      label: t(warehouseSection === "input"
        ? "stock.nav.inputWarehouse"
        : warehouseSection === "purchaseDeliveryNotes"
          ? "warehouseScreen.purchaseDeliveryNotes"
          : warehouseSection === "purchaseInvoices"
            ? "warehouseScreen.purchaseInvoices"
        : warehouseSection === "output"
          ? "stock.nav.outputWarehouse"
          : "warehouseScreen.goodsCheck"),
      onOpen: () => onOpenStock({
        key: `stock.warehouse.${warehouseSection}`,
        warehouseSection
      })
    }))
  ];
  const partyItems: GestionNavigationItem[] = [
    ...(canReadCustomers ? [{
      key: "stock.party.customers",
      label: t("party.customers.title"),
      onOpen: () => onOpenStock({ key: "stock.party.customers", partyDirectory: "customers" })
    }, {
      key: "stock.party.members.group",
      label: t("party.members.title"),
      children: [{
        key: "stock.party.members",
        label: t("party.members.directory"),
        onOpen: () => onOpenStock({ key: "stock.party.members", partyDirectory: "members" })
      }, ...(canConfigurePaymentMethods ? [{
        key: "memberCategories",
        label: t("gestion.memberCategories.navigation"),
        onOpen: onOpenMemberCategories
      }] : [])]
    }] : []),
    ...(canReadSuppliers ? [{
      key: "stock.party.suppliers",
      label: t("party.suppliers.title"),
      onOpen: () => onOpenStock({ key: "stock.party.suppliers", partyDirectory: "suppliers" })
    }] : [])
  ];
  const stockContentItems = [
    ...stockChildren,
    ...warehouseChildren,
    ...partyItems.flatMap((item) => item.children?.length ? item.children : [item])
  ];
  const securityChildren: GestionNavigationItem[] = [
    ...(modules.includes("gestion.users")
      ? [{ key: "users", label: t("gestion.users.navigation"), onOpen: onOpenUsers }]
      : []),
    ...((session.permissions.includes("ADMIN") || session.permissions.includes("TERMINALS_MANAGE"))
      ? [{ key: "terminals", label: t("gestion.terminals.navigation"), onOpen: onOpenTerminals }]
      : []),
    ...(modules.includes("gestion.roles")
      ? [{ key: "roles", label: t("gestion.roles.navigation"), onOpen: onOpenRoles }]
      : []),
    ...(canConfigurePaymentMethods
      ? [{ key: "salesOperationSecurity", label: t("gestion.salesOperationSecurity.navigation"), onOpen: onOpenSalesOperationSecurity }]
      : [])
  ];

  const cashChildren: GestionNavigationItem[] = [
    ...(modules.includes("gestion.cashCurrentBalances")
      ? [{
          key: "cashCurrentBalances",
          label: t("gestion.cashCurrentBalances.navigation"),
          onOpen: onOpenCashCurrentBalances
        }]
      : []),
    ...(modules.includes("gestion.cashClosures")
      ? [{
          key: "cashClosures",
          label: t("gestion.cashClosures.navigation"),
          onOpen: onOpenCashClosures
        }]
      : [])
  ];

  const legacyNavigation: GestionNavigationItem[] = [
    { key: "dashboard", label: t("gestion.dashboard"), onOpen: onOpenDashboard },
    ...(verifactuAllowed
      ? [{ key: "verifactu", label: t("verifactu.management.navigation"), onOpen: onOpenVerifactu }]
      : []),
    ...(modules.includes("gestion.controlAlerts")
      ? [{ key: "controlAlerts", label: t("gestion.controlAlerts.navigation"), onOpen: onOpenControlAlerts }]
      : []),
    ...(cashChildren.length > 0
      ? [{
          key: "cash",
          label: t("gestion.cash.navigation"),
          children: cashChildren
        }]
      : []),
    ...(modules.includes("gestion.sales")
      ? [{
          key: "sales",
          label: t("gestion.sales"),
          children: [
            ...reports.map((report) => ({ key: report, label: t(report), onOpen: () => onOpenSales(report) })),
            { key: "vouchers", label: t("gestion.vouchers.navigation"), onOpen: onOpenVouchers }
          ]
        }]
      : []),
    ...(modules.includes("gestion.stock")
      && stockChildren.length > 0
      ? [{ key: "stock", label: t("gestion.stock"), children: stockChildren }]
      : []),
    ...(warehouseChildren.length > 0
      ? [{ key: "warehouse", label: t("stock.warehouse"), children: warehouseChildren }]
      : []),
    ...partyItems,
    ...(canManageProducts
      ? [{ key: "promotions", label: t("promotion.list.heading"), onOpen: onOpenPromotions }]
      : []),
    ...(securityChildren.length > 0
      ? [{ key: "security", label: t("gestion.security.navigation"), children: securityChildren }]
      : []),
    ...(canConfigurePaymentMethods || canManageTaxesForSession || canManageDocumentTemplates || canReadLicenses || canManageFamiliesForSession || canManageProducts || canReadCustomers || canReadSuppliers
      ? [{
          key: "configuration",
          label: t("gestion.configuration.navigation"),
          children: [...(canManageDocumentTemplates ? [{
            key: "documentTemplates",
            label: t("gestion.documentTemplates.navigation"),
            onOpen: onOpenDocumentTemplates
          }] : []), ...(canReadLicenses ? [{
            key: "licenses",
            label: t("gestion.licenses.navigation"),
            onOpen: onOpenLicenses
          }] : []), ...(canManageTaxesForSession ? [{
            key: "taxes",
            label: t("gestion.taxes.navigation"),
            onOpen: onOpenTaxes
          }] : []), ...(canConfigurePaymentMethods ? [{
            key: "documentPrintSettings",
            label: t("gestion.documentPrint.navigation"),
            onOpen: onOpenDocumentPrintSettings
          }, {
            key: "voucherSettings",
            label: t("gestion.voucherSettings.navigation"),
            onOpen: onOpenVoucherSettings
          }, {
            key: "memberLoyaltySettings",
            label: t("gestion.memberSettings.navigation"),
            onOpen: onOpenMemberLoyaltySettings
          }, {
            key: "paymentMethods",
            label: t("gestion.paymentMethods.navigation"),
            onOpen: onOpenPaymentMethods
          }, {
            key: "salesOperationSecurity",
            label: t("gestion.salesOperationSecurity.navigation"),
            onOpen: onOpenSalesOperationSecurity
          }, {
            key: "internalEan",
            label: t("gestion.internalEan.navigation"),
            onOpen: onOpenInternalEan
           }] : []), ...(canManageFamiliesForSession ? [{
             key: "families",
             label: t("gestion.families.navigation"),
             onOpen: onOpenFamilies
          }] : []), ...(canConfigurePaymentMethods ? [{
             key: "productManagement",
             label: t("gestion.products"),
             onOpen: onOpenProductManagement
           }] : []), ...(canConfigurePaymentMethods ? [{
             key: "customerManagement",
             label: t("gestion.customers"),
             onOpen: onOpenCustomerManagement
           }] : []), ...(canConfigurePaymentMethods ? [{
             key: "supplierManagement",
             label: t("gestion.suppliers"),
             onOpen: onOpenSupplierManagement
           }] : [])]
        }]
      : [])
  ];

  const legacyDestinations = new Map<string, GestionNavigationItem>();
  function collectLegacyDestinations(items: GestionNavigationItem[]) {
    items.forEach((item) => {
      if (item.onOpen) legacyDestinations.set(item.key, item);
      if (item.children) collectLegacyDestinations(item.children);
    });
  }
  collectLegacyDestinations(legacyNavigation);
  const destinationAliases: Record<string, string> = {
    "cash.dailySales": "salesReport.dailySales",
    "sales.vouchers": "vouchers",
    "salesReport.vouchers": "vouchers",
    "supplierReport.inputInvoices": "salesReport.inputInvoices",
    "supplierReport.inputDeliveryNotes": "salesReport.inputDeliveryNotes",
    "supplierReport.inputWarehouse": "salesReport.inputWarehouse",
    "supplierReport.purchaseDeliveryNotes": "stock.warehouse.purchaseDeliveryNotes",
    "supplierReport.purchaseInvoices": "stock.warehouse.purchaseInvoices",
    "memberCategories.configuration": "memberCategories",
    "families.configuration": "families",
  };
  const navigation: GestionNavigationItem[] = gestionNavigationGroups.flatMap((group) => {
    const destinations = group.destinations
      .map((registered) => {
        const source = legacyDestinations.get(registered.key)
          ?? legacyDestinations.get(destinationAliases[registered.key] ?? "");
        if (!source?.onOpen) return null;
        return {
          key: registered.key,
          label: t(registered.labelKey),
          icon: registered.icon,
          lock: registered.lock,
          onOpen: source.onOpen,
        } satisfies GestionNavigationItem;
      })
      .filter((item) => item !== null) as GestionNavigationItem[];
    if (group.direct && destinations.length === 1) {
      return destinations;
    }
    return destinations.length > 0 ? [{
      key: group.key,
      label: t(group.labelKey),
      icon: group.icon,
      lock: group.lock,
      children: destinations,
    }] : [];
  });

  const activeKey = effectiveModule === "sales"
    ? salesReport
    : effectiveModule === "stock"
      ? stockSelection.key
      : effectiveModule;

  function requestOpenDestination(item: GestionNavigationItem): boolean | Promise<boolean> {
    if (!item.lock || unlockedGroups.has(item.lock)) return true;
    return new Promise<boolean>((resolve) => setUnlockRequest({ group: item.lock!, resolve }));
  }

  function completeUnlock() {
    if (!unlockRequest) return;
    const group = unlockRequest.group;
    const resolve = unlockRequest.resolve;
    setUnlockedGroups((current) => new Set(current).add(group));
    setUnlockRequest(null);
    resolve(true);
  }

  function cancelUnlock() {
    if (!unlockRequest) return;
    unlockRequest.resolve(false);
    setUnlockRequest(null);
  }

  function lockedGroupFallback() {
    setUnlockedGroups(new Set());
    cancelUnlock();
    onOpenDashboard();
  }

  useEffect(() => {
    const handleLockedGroup = () => {
      if (!unlockRequest) lockedGroupFallback();
    };
    window.addEventListener(gestionGroupLockedEvent, handleLockedGroup);
    return () => window.removeEventListener(gestionGroupLockedEvent, handleLockedGroup);
  });

  let content;
  if (effectiveModule === "verifactu" && verifactuAllowed) {
    content = <VerifactuManagementScreen locale={locale} session={session} t={t} />;
  } else if (effectiveModule === "controlAlerts" && modules.includes("gestion.controlAlerts")) {
    content = <ControlAlertsScreen session={session} t={t} />;
  } else if (effectiveModule === "cashClosures" && modules.includes("gestion.cashClosures")) {
    content = <CashClosuresScreen session={session} t={t} />;
  } else if (effectiveModule === "cashCurrentBalances" && modules.includes("gestion.cashCurrentBalances")) {
    content = <CashCurrentBalancesScreen session={session} t={t} />;
  } else if (effectiveModule === "sales" && reports.includes(salesReport)) {
    content = (
      <SalesReportScreen
        key={salesReport}
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialReport={salesReport}
      />
    );
  } else if (effectiveModule === "vouchers" && modules.includes("gestion.sales")) {
    content = (
      <VoucherManagementScreen
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        t={t}
      />
    );
  } else if (effectiveModule === "stock" && stockContentItems.some((item) => item.key === stockSelection.key)) {
    content = stockSelection.warehouseOperation ? (
      <WarehouseOperationsScreen
        key={stockSelection.key}
        session={session}
        mode={stockSelection.warehouseOperation}
        t={t}
      />
    ) : stockSelection.warehouseManagement ? (
      <WarehouseManagementScreen session={session} t={t} />
    ) : stockSelection.warehouseSection ? (
      <WarehouseScreen
        key={stockSelection.key}
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialSection={stockSelection.warehouseSection}
      />
    ) : (
      <StockScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialView={stockSelection.view}
        initialPartyDirectory={stockSelection.partyDirectory}
        initialSettingsMode={stockSelection.settingsMode}
      />
    );
  } else if (effectiveModule === "promotions" && canManageProducts) {
    content = (
      <PromotionListScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
      />
    );
  } else if (effectiveModule === "users" && modules.includes("gestion.users")) {
    content = <SecurityAdministrationScreen mode="users" session={session} t={t} />;
  } else if (effectiveModule === "terminals" && (session.permissions.includes("ADMIN") || session.permissions.includes("TERMINALS_MANAGE"))) {
    content = <TerminalManagementScreen session={session} t={t} />;
  } else if (effectiveModule === "roles" && modules.includes("gestion.roles")) {
    content = <SecurityAdministrationScreen mode="roles" session={session} t={t} />;
  } else if (effectiveModule === "paymentMethods" && canConfigurePaymentMethods) {
    content = <PaymentMethodSettingsScreen session={session} t={t} />;
  } else if (effectiveModule === "taxes" && canManageTaxesForSession) {
    content = <TaxSettingsScreen session={session} t={t} />;
  } else if (effectiveModule === "salesOperationSecurity" && canConfigurePaymentMethods) {
    content = <SalesOperationSecurityScreen session={session} t={t} />;
  } else if (effectiveModule === "memberLoyaltySettings" && canConfigurePaymentMethods) {
    content = <MemberLoyaltySettingsScreen session={session} t={t} />;
  } else if (effectiveModule === "memberCategories" && canConfigurePaymentMethods) {
    content = <MemberCategoriesScreen session={session} t={t} />;
  } else if (effectiveModule === "internalEan" && canConfigurePaymentMethods) {
    content = <InternalEanSettingsScreen session={session} t={t} />;
  } else if (effectiveModule === "documentTemplates" && canManageDocumentTemplates) {
    content = <DocumentTemplateSettingsScreen session={session} t={t} />;
  } else if (effectiveModule === "documentPrintSettings" && canConfigurePaymentMethods) {
    content = <StoreDocumentPrintSettingsScreen session={session} storeName={terminalContext.storeName} t={t} />;
  } else if (effectiveModule === "voucherSettings" && canConfigurePaymentMethods) {
    content = <VoucherSettingsScreen session={session} storeName={terminalContext.storeName} t={t} />;
  } else if (effectiveModule === "licenses" && canReadLicenses) {
    content = <LicenseSaasManagementScreen locale={locale} session={session} storeName={terminalContext.storeName} t={t} />;
  } else if (effectiveModule === "families" && canManageFamiliesForSession) {
    content = <FamiliesScreen session={session} t={t} />;
  } else if (effectiveModule === "productManagement" && canConfigurePaymentMethods) {
    content = (
      <StockScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialView="stock.current"
        allowSafeRetirement
      />
    );
  } else if (effectiveModule === "customerManagement" && canConfigurePaymentMethods) {
    content = (
      <StockScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={terminalContext}
        onBack={onOpenDashboard}
        onLogout={onLogout}
        onLocaleChange={onLocaleChange}
        embedded
        initialPartyDirectory="customers"
        allowSafeRetirement
      />
    );
  } else if (effectiveModule === "supplierManagement" && canConfigurePaymentMethods) {
    content = <SupplierManagement session={session} locale={locale} />;
  } else {
    content = (
      <GestionDashboard
        session={session}
        locale={locale}
        t={t}
        onOpenSales={() => onOpenSales(reports[0] ?? "salesReport.dailySales")}
        onOpenStock={() => onOpenStock({ key: stockViews[0] ?? "stock.current", view: stockViews[0] ?? "stock.current" })}
        onOpenPromotions={onOpenPromotions}
        onOpenControlAlerts={onOpenControlAlerts}
      />
    );
  }

  return (
    <GestionShell
      session={session}
      t={t}
      activeKey={activeKey}
      navigation={navigation}
      requestOpenDestination={requestOpenDestination}
    >
      <section className="gestion-module-stage">
        <Suspense fallback={<div className="gestion-module-loading">{t("common.loading")}</div>}>
          {content}
        </Suspense>
      </section>
      {unlockRequest && (
        <GestionGroupUnlockDialog
          group={unlockRequest.group}
          session={session}
          t={t}
          onUnlocked={completeUnlock}
          onLocked={lockedGroupFallback}
          onCancel={cancelUnlock}
        />
      )}
    </GestionShell>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Suspense fallback={null}>
      <App />
    </Suspense>
  </StrictMode>
);
