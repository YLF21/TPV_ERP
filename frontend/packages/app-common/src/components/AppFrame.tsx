import type { LocaleCode, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { useRef, useState, type ReactNode } from "react";
import languageIcon from "../assets/language.png";
import { useOutsidePointerDown } from "./useOutsidePointerDown";

type AppFrameProps = {
  titleKey: string;
  locale: LocaleCode;
  session: UserSession;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout: () => void;
  children: ReactNode;
};

const languageOptions: Array<{ code: LocaleCode; label: string }> = [
  { code: "es", label: "Español" },
  { code: "en", label: "English" },
  { code: "zh", label: "中文" }
];

export function AppFrame({ titleKey, locale, session, onLocaleChange, onLogout, children }: AppFrameProps) {
  const t = createTranslator(locale);
  const [languageOpen, setLanguageOpen] = useState(false);
  const languagePickerRef = useRef<HTMLDivElement | null>(null);

  useOutsidePointerDown(languageOpen, languagePickerRef, () => setLanguageOpen(false));

  return (
    <div className="app-frame">
      <header className="app-titlebar">
        <strong>{t(titleKey)}</strong>
        <span>{t("login.serverContext")}</span>
        <span className="app-titlebar-status">{session.displayName} · {t("common.localStatus")}</span>
        <div className="app-titlebar-language" ref={languagePickerRef}>
          <button
            type="button"
            className="app-titlebar-language-button"
            aria-expanded={languageOpen}
            aria-haspopup="listbox"
            aria-label={t("login.language")}
            title={t("login.language")}
            onClick={() => setLanguageOpen((open) => !open)}
          >
            <img alt="" src={languageIcon} />
            <span>{locale.toUpperCase()}</span>
          </button>
          {languageOpen && (
            <section className="app-titlebar-language-picker" aria-label={t("login.language")}>
              {languageOptions.map((option) => (
                <button
                  type="button"
                  className={option.code === locale ? "selected" : undefined}
                  aria-pressed={option.code === locale}
                  key={option.code}
                  onClick={() => {
                    onLocaleChange(option.code);
                    setLanguageOpen(false);
                  }}
                >
                  <span>{option.label}</span>
                  <strong>{option.code.toUpperCase()}</strong>
                </button>
              ))}
            </section>
          )}
        </div>
        <button type="button" onClick={onLogout}>{t("common.logout")}</button>
      </header>
      {children}
    </div>
  );
}
