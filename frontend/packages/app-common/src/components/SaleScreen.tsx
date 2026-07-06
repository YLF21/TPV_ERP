import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";

type SaleScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
};

const ticketLines = [
  { name: "Cafe molido 250 g", quantity: "2", price: "4,50", total: "9,00" },
  { name: "Pan integral", quantity: "1", price: "2,10", total: "2,10" },
  { name: "Leche fresca", quantity: "3", price: "1,35", total: "4,05" }
];

export function SaleScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout
}: SaleScreenProps) {
  const t = createTranslator(locale);

  return (
    <main className="sale-screen work-screen">
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

      <section className="work-shell" aria-label="Venta">
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">Venta</h1>
        </header>

        <section className="sale-ticket work-panel" aria-label="Ticket actual">
          <header className="work-panel-heading">
            <h2>Ticket actual</h2>
            <span>Mesa de venta directa</span>
          </header>
          <div className="sale-ticket-lines">
            {ticketLines.map((line) => (
              <article className="sale-ticket-line" key={line.name}>
                <strong>{line.name}</strong>
                <span>{line.quantity} x {line.price}</span>
                <b>{line.total}</b>
              </article>
            ))}
          </div>
          <footer className="sale-total">
            <span>Total</span>
            <strong>15,15</strong>
          </footer>
        </section>

        <section className="sale-tools work-panel" aria-label="Busqueda y cobro">
          <div className="work-panel-heading">
            <h2>Producto</h2>
            <span>Entrada rapida por codigo, nombre o referencia</span>
          </div>
          <label className="work-search">
            <span>Buscar producto</span>
            <input placeholder="Codigo o nombre" />
          </label>
          <div className="sale-quick-grid">
            <button type="button">Cantidad</button>
            <button type="button">Descuento</button>
            <button type="button">Cliente</button>
            <button type="button">Anular linea</button>
          </div>
          <section className="sale-payment" aria-label="Cobro">
            <h2>Cobro</h2>
            <div className="sale-payment-actions">
              <button type="button">Efectivo</button>
              <button type="button">Tarjeta</button>
              <button type="button">Pendiente cliente</button>
            </div>
          </section>
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}
