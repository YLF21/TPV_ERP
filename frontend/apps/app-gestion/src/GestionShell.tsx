import type { ReactNode } from "react";
import type { UserSession } from "@tpverp/app-common";

type Translator = (key: string) => string;

export type GestionNavigationItem = {
  key: string;
  label: string;
  onOpen: () => void;
};

type GestionShellProps = {
  session: UserSession;
  t: Translator;
  activeKey: string;
  navigation: GestionNavigationItem[];
  children: ReactNode;
};

export function GestionShell({
  session,
  t,
  activeKey,
  navigation,
  children
}: GestionShellProps) {
  return (
    <main className="gestion-screen">
      <aside className="gestion-nav">
        <div className="gestion-nav-brand">
          <h1>{t("gestion.title")}</h1>
          <p>{t("gestion.subtitle")}</p>
        </div>
        <nav aria-label={t("gestion.navigation") }>
          {navigation.map((item) => (
            <button
              type="button"
              key={item.key}
              className={item.key === activeKey ? "selected" : undefined}
              aria-current={item.key === activeKey ? "page" : undefined}
              onClick={item.onOpen}
            >
              {item.label}
            </button>
          ))}
        </nav>
        <div className="gestion-nav-context">
          <span>{session.displayName}</span>
          <small>{t("gestion.localStore")}</small>
        </div>
      </aside>
      {children}
    </main>
  );
}
