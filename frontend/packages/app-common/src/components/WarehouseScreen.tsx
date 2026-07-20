import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { GoodsCheckPanel } from "./GoodsCheckPanel";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { WarehouseOperationsPanel } from "./WarehouseOperationsPanel";
import type {
  WarehouseCustomerOption,
  WarehouseOption,
  WarehouseSupplierOption
} from "./WarehouseDocumentDialog";
import type { WarehouseImportProduct } from "./warehouseDocumentImport";
import { userCanManageWarehouse, type WarehouseSection } from "./warehouseAccess";
export {
  userCanManageWarehouse,
  visibleWarehouseSectionsForSession,
  warehouseSections
} from "./warehouseAccess";
export type { WarehouseSection } from "./warehouseAccess";

type WarehouseScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLogout?: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  embedded?: boolean;
  initialSection?: WarehouseSection;
};

type ProductOptionView = WarehouseImportProduct & {
  barcode2?: string | null;
};

type WarehouseView = WarehouseOption & {
  active?: boolean;
  defaultWarehouse?: boolean;
};

export function WarehouseScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLogout,
  onLocaleChange,
  embedded = false,
  initialSection = "input"
}: WarehouseScreenProps) {
  const t = createTranslator(locale);
  const [section, setSection] = useState<WarehouseSection>(initialSection);
  const [products, setProducts] = useState<WarehouseImportProduct[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseView[]>([]);
  const [customers, setCustomers] = useState<WarehouseCustomerOption[]>([]);
  const [suppliers, setSuppliers] = useState<WarehouseSupplierOption[]>([]);
  const [status, setStatus] = useState("");
  const canManage = userCanManageWarehouse(session);
  const defaultWarehouseId = useMemo(() => (
    warehouses.find((warehouse) => warehouse.defaultWarehouse)?.id
      ?? warehouses.find((warehouse) => warehouse.active !== false)?.id
  ), [warehouses]);

  useEffect(() => {
    setSection(initialSection);
  }, [initialSection]);

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken || !canManage) return;
    const token = session.accessToken;
    void Promise.all([
      apiRequest<ProductOptionView[]>("/products", { token }),
      apiRequest<WarehouseView[]>("/warehouses", { token }),
      apiRequest<WarehouseCustomerOption[]>("/customers/sale-options", { token }),
      apiRequest<WarehouseSupplierOption[]>("/suppliers", { token })
    ]).then(([loadedProducts, loadedWarehouses, loadedCustomers, loadedSuppliers]) => {
      if (cancelled) return;
      setProducts(loadedProducts.map((product) => ({
        ...product,
        reference: product.reference ?? product.barcode2 ?? undefined
      })));
      setWarehouses(loadedWarehouses.filter((warehouse) => warehouse.active !== false));
      setCustomers(loadedCustomers);
      setSuppliers(loadedSuppliers);
      setStatus("");
    }).catch((error) => {
      if (!cancelled) setStatus(error instanceof Error ? error.message : t("warehouseScreen.loadError"));
    });
    return () => {
      cancelled = true;
    };
  }, [canManage, locale, session.accessToken]);

  const titleKey = section === "input"
    ? "stock.nav.inputWarehouse"
    : section === "output"
      ? "stock.nav.outputWarehouse"
      : "warehouseScreen.goodsCheck";
  const subtitleKey = section === "input"
    ? "warehouseOperations.inputSubtitle"
    : section === "output"
      ? "warehouseOperations.outputSubtitle"
      : "warehouseScreen.goodsCheckSubtitle";

  return (
    <main className={embedded
      ? "stock-screen work-screen warehouse-screen gestion-embedded-module"
      : "stock-screen work-screen warehouse-screen"}
    >
      {!embedded && <SessionTopControls
        locale={locale}
        session={session}
        languageLabel={t("login.language")}
        shutdownLabel={t("login.shutdown")}
        changePasswordLabel={t("common.changePassword")}
        logoutLabel={t("common.logout")}
        shutdownConfirmTitle={t("login.shutdownConfirmTitle")}
        shutdownConfirmText={t("login.shutdownConfirmText")}
        noLabel={t("common.no")}
        yesLabel={t("common.yes")}
        onLocaleChange={onLocaleChange}
        onLogout={onLogout}
      />}

      <section className="work-shell" aria-label={t("home.warehouse")}>
        <header className="work-topbar">
          {!embedded && <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>}
          <h1 className="report-title">{t("home.warehouse")}</h1>
        </header>

        {!embedded && <aside className="stock-nav">
          <strong>{t("home.warehouse")}</strong>
          <button type="button" className={section === "input" ? "selected" : ""} onClick={() => setSection("input")}>
            {t("stock.nav.inputWarehouse")}
          </button>
          <button type="button" className={section === "output" ? "selected" : ""} onClick={() => setSection("output")}>
            {t("stock.nav.outputWarehouse")}
          </button>
          <button type="button" className={section === "goodsCheck" ? "selected" : ""} onClick={() => setSection("goodsCheck")}>
            {t("warehouseScreen.goodsCheck")}
          </button>
          <button type="button" className="report-back" onClick={onBack}>{t("common.back")}</button>
        </aside>}

        <section className="stock-list work-panel" aria-label={t(titleKey)}>
          <header className="work-panel-heading stock-panel-heading">
            <div>
              <h2>{t(titleKey)}</h2>
              <span>{t(subtitleKey)}</span>
            </div>
          </header>
          {!canManage ? (
            <div className="stock-empty-state">{t("warehouseScreen.noAccess")}</div>
          ) : section === "goodsCheck" ? (
            <GoodsCheckPanel locale={locale} token={session.accessToken} t={t} />
          ) : (
            <WarehouseOperationsPanel
              mode={section}
              app={app}
              username={session.username}
              accessToken={session.accessToken}
              token={session.accessToken}
              products={products}
              warehouses={warehouses}
              customers={customers}
              suppliers={suppliers}
              t={t}
              locale={locale}
              terminalContext={terminalContext}
              defaultWarehouseId={defaultWarehouseId}
              permissions={{ read: true, create: true, edit: true, delete: true, canConfirm: true }}
              onError={(error) => setStatus(error instanceof Error ? error.message : t("warehouseScreen.operationError"))}
            />
          )}
          {status && <p className="stock-operation-status error" role="alert">{status}</p>}
        </section>
        {embedded && <ScreenContextFooter locale={locale} terminalContext={terminalContext} />}
      </section>

      {!embedded && <ScreenContextFooter locale={locale} terminalContext={terminalContext} />}
    </main>
  );
}

export default WarehouseScreen;
