import { useEffect, useState } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";

type StockScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};

type StockItemView = {
  productId: string;
  warehouseId: string;
  quantity: number;
};

export function StockScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout
}: StockScreenProps) {
  const t = createTranslator(locale);
  const [stockRows, setStockRows] = useState<StockItemView[]>([]);
  const [status, setStatus] = useState("Sin datos de stock");

  useEffect(() => {
    let cancelled = false;
    if (!session.accessToken) {
      setStockRows([]);
      setStatus("Sin datos de stock");
      return;
    }

    async function loadStock() {
      try {
        const rows = await apiRequest<StockItemView[]>("/stock", { token: session.accessToken });
        if (!cancelled) {
          setStockRows(rows);
          setStatus(rows.length === 0 ? "Sin datos de stock" : "Stock cargado desde base de datos");
        }
      } catch (error) {
        if (!cancelled) {
          setStockRows([]);
          setStatus(error instanceof Error ? error.message : "Sin datos de stock");
        }
      }
    }

    void loadStock();
    return () => {
      cancelled = true;
    };
  }, [session.accessToken]);

  return (
    <main className="stock-screen work-screen">
      <SessionTopControls
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
      />

      <section className="work-shell" aria-label="Stock">
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">Stock</h1>
        </header>

        <section className="stock-list work-panel" aria-label="Inventario">
          <header className="work-panel-heading">
            <h2>Inventario</h2>
            <span>Consulta rapida de existencias por terminal</span>
          </header>
          <div className="stock-toolbar">
            <label className="work-search">
              <span>Buscar articulo</span>
              <input placeholder="Codigo, nombre o ubicacion" />
            </label>
            <button type="button">Bajo minimo</button>
            <button type="button">Todos</button>
          </div>
          <div className="stock-table">
            <div className="stock-row stock-row-head">
              <span>Producto</span>
              <span>Almacen</span>
              <span>Stock</span>
              <span>Estado</span>
            </div>
            {stockRows.length === 0 && <div className="stock-empty-state">{status}</div>}
            {stockRows.map((row) => (
              <article className="stock-row" key={`${row.productId}-${row.warehouseId}`}>
                <strong>{row.productId}</strong>
                <span>{row.warehouseId}</span>
                <b>{row.quantity}</b>
                <em>{status}</em>
              </article>
            ))}
          </div>
        </section>

        <aside className="stock-actions work-panel">
          <header className="work-panel-heading">
            <h2>Movimientos</h2>
            <span>Acciones preparadas para conectar con almacen</span>
          </header>
          <button type="button">Entrada stock</button>
          <button type="button">Salida stock</button>
          <button type="button">Ajuste inventario</button>
          <button type="button">Imprimir listado</button>
        </aside>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}
