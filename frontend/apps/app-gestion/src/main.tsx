import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { AppFrame, LoginScreen, createTranslator, devTerminalContext, type LocaleCode, type UserSession } from "@tpverp/app-common";
import "./gestion.css";

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [session, setSession] = useState<UserSession | null>(null);

  if (!session) {
    return (
      <LoginScreen
        app="gestion"
        locale={locale}
        terminalContext={devTerminalContext}
        onLocaleChange={setLocale}
        onLogin={setSession}
      />
    );
  }

  return (
    <AppFrame titleKey="gestion.title" locale={locale} session={session} onLogout={() => setSession(null)}>
      <GestionScreen locale={locale} />
    </AppFrame>
  );
}

function GestionScreen({ locale }: { locale: LocaleCode }) {
  const t = createTranslator(locale);
  const modules = [
    "gestion.sales",
    "gestion.products",
    "gestion.stock",
    "gestion.customers",
    "gestion.suppliers",
    "gestion.users"
  ];

  return (
    <main className="gestion-screen">
      <aside className="gestion-nav">
        <h1>{t("gestion.title")}</h1>
        <p>{t("gestion.subtitle")}</p>
        {modules.map((moduleKey) => (
          <button key={moduleKey}>{t(moduleKey)}</button>
        ))}
      </aside>
      <section className="gestion-workspace">
        <header>
          <h2>{t("gestion.products")}</h2>
          <span>{t("common.ready")}</span>
        </header>
        <div className="gestion-grid">
          {modules.map((moduleKey) => (
            <article key={moduleKey}>
              <strong>{t(moduleKey)}</strong>
              <p>{t("gestion.placeholder")}</p>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
