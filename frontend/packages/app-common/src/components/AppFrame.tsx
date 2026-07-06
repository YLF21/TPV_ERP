import type { LocaleCode, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { ReactNode } from "react";

type AppFrameProps = {
  titleKey: string;
  locale: LocaleCode;
  session: UserSession;
  onLogout: () => void;
  children: ReactNode;
};

export function AppFrame({ titleKey, locale, session, onLogout, children }: AppFrameProps) {
  const t = createTranslator(locale);

  return (
    <div className="app-frame">
      <header className="app-titlebar">
        <strong>{t(titleKey)}</strong>
        <span>{t("login.serverContext")}</span>
        <span className="app-titlebar-status">{session.displayName} · {t("common.localStatus")}</span>
        <button type="button" onClick={onLogout}>{t("common.logout")}</button>
      </header>
      {children}
    </div>
  );
}
