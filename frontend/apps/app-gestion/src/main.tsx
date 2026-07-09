import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AppFrame,
  LoginScreen,
  PromotionListScreen,
  createTranslator,
  devTerminalContext,
  type LocaleCode,
  type UserSession
} from "@tpverp/app-common";
import "./gestion.css";

type GestionModule = "dashboard" | "promotions";

function App() {
  const [locale, setLocale] = useState<LocaleCode>("es");
  const [session, setSession] = useState<UserSession | null>(null);
  const [module, setModule] = useState<GestionModule>("dashboard");

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

  if (module === "promotions") {
    return (
      <PromotionListScreen
        app="gestion"
        locale={locale}
        session={session}
        terminalContext={devTerminalContext}
        onBack={() => setModule("dashboard")}
        onLogout={() => setSession(null)}
        onLocaleChange={setLocale}
      />
    );
  }

  return (
    <AppFrame titleKey="gestion.title" locale={locale} session={session} onLogout={() => setSession(null)}>
      <GestionScreen locale={locale} onOpenPromotions={() => setModule("promotions")} />
    </AppFrame>
  );
}

function GestionScreen({ locale, onOpenPromotions }: { locale: LocaleCode; onOpenPromotions: () => void }) {
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
        <button type="button" onClick={onOpenPromotions}>{t("promotion.list.heading")}</button>
      </aside>
      <section className="gestion-workspace">
        <header>
          <h2>{t("promotion.list.heading")}</h2>
          <span>{t("common.ready")}</span>
        </header>
        <div className="gestion-grid">
          <article>
            <strong>{t("promotion.list.heading")}</strong>
            <p>{t("promotion.coupon.placeholder")}</p>
            <button type="button" onClick={onOpenPromotions}>{t("promotion.list.heading")}</button>
          </article>
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
