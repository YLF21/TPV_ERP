import { useEffect, useState, type ReactNode } from "react";
import type { UserSession } from "@tpverp/app-common";

type Translator = (key: string) => string;

export type GestionNavigationItem = {
  key: string;
  label: string;
  onOpen?: () => void;
  children?: GestionNavigationItem[];
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
  const activeParent = navigation.find((item) => item.children?.some((child) => child.key === activeKey))?.key;
  const [expandedKey, setExpandedKey] = useState<string | null>(activeParent ?? null);

  useEffect(() => {
    setExpandedKey(activeParent ?? null);
  }, [activeParent]);

  return (
    <main className="gestion-screen">
      <aside className="gestion-nav">
        <div className="gestion-nav-brand">
          <h1>{t("gestion.title")}</h1>
          <p>{t("gestion.subtitle")}</p>
        </div>
        <nav aria-label={t("gestion.navigation") }>
          {navigation.map((item) => {
            const hasChildren = Boolean(item.children?.length);
            const groupOpen = expandedKey === item.key;
            const groupActive = item.children?.some((child) => child.key === activeKey) ?? false;
            return (
              <div className={`gestion-nav-item ${hasChildren ? "group" : "direct"}`} key={item.key}>
                <button
                  type="button"
                  className={item.key === activeKey || groupActive ? "selected" : undefined}
                  aria-current={item.key === activeKey ? "page" : undefined}
                  aria-expanded={hasChildren ? groupOpen : undefined}
                  onClick={() => {
                    if (hasChildren) {
                      setExpandedKey((current) => current === item.key ? null : item.key);
                      return;
                    }
                    setExpandedKey(null);
                    item.onOpen?.();
                  }}
                >
                  <span>{item.label}</span>
                  {hasChildren && <i aria-hidden="true">{groupOpen ? "−" : "+"}</i>}
                </button>
                {hasChildren && groupOpen && (
                  <div className="gestion-nav-children">
                    {item.children?.map((child) => (
                      <button
                        type="button"
                        key={child.key}
                        className={child.key === activeKey ? "selected" : undefined}
                        aria-current={child.key === activeKey ? "page" : undefined}
                        onClick={child.onOpen}
                      >
                        {child.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
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
