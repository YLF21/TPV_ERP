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

const stockRows = [
  { sku: "CAF-250", name: "Cafe molido 250 g", stock: 42, minimum: 12, location: "A-01", status: "OK" },
  { sku: "PAN-INT", name: "Pan integral", stock: 8, minimum: 10, location: "B-03", status: "Bajo" },
  { sku: "LEC-FRE", name: "Leche fresca", stock: 24, minimum: 18, location: "FR-01", status: "OK" },
  { sku: "ACE-1L", name: "Aceite oliva 1 l", stock: 3, minimum: 8, location: "C-02", status: "Critico" }
];

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
              <span>Codigo</span>
              <span>Articulo</span>
              <span>Stock</span>
              <span>Stock minimo</span>
              <span>Ubicacion</span>
              <span>Estado</span>
            </div>
            {stockRows.map((row) => (
              <article className={`stock-row stock-status-${row.status.toLowerCase()}`} key={row.sku}>
                <strong>{row.sku}</strong>
                <span>{row.name}</span>
                <b>{row.stock}</b>
                <span>{row.minimum}</span>
                <span>{row.location}</span>
                <em>{row.status}</em>
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
