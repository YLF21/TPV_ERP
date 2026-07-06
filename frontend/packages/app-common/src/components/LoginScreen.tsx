import { FormEvent, useEffect, useMemo, useState } from "react";
import { ApiError } from "../api/client";
import { authenticateRemote } from "../auth/auth";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ScreenContextFooter } from "./ScreenContextFooter";
import languageIcon from "../assets/language.png";

type LoginScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  terminalContext: TerminalContext;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogin: (session: UserSession) => void;
};

const languageOptions: Array<{ code: LocaleCode; label: string }> = [
  { code: "es", label: "Español" },
  { code: "en", label: "English" },
  { code: "zh", label: "中文" }
];

export function LoginScreen({ app, locale, terminalContext, onLocaleChange, onLogin }: LoginScreenProps) {
  const t = createTranslator(locale);
  const historyKey = useMemo(() => `tpverp.${app}.loginUsers`, [app]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [userHistory, setUserHistory] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [languageOpen, setLanguageOpen] = useState(false);
  const [shutdownOpen, setShutdownOpen] = useState(false);

  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(historyKey);
      setUserHistory(stored ? JSON.parse(stored) : []);
    } catch {
      setUserHistory([]);
    }
  }, [historyKey]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const normalizedUsername = username.trim();
      const session = await authenticateRemote(normalizedUsername, password, app, terminalContext);
      rememberUser(normalizedUsername);
      onLogin(session);
    } catch (caught) {
      const message =
        caught instanceof Error && caught.message === "no_access"
          ? t("login.noAccess")
          : caught instanceof Error && caught.message === "terminal_not_configured"
            ? t("login.terminalMissing")
            : caught instanceof ApiError && caught.status === 401
              ? t("login.invalid")
              : t("login.connectionError");
      setError(message);
    } finally {
      setLoading(false);
    }
  }

  function rememberUser(value: string) {
    if (!value) {
      return;
    }
    const next = [value, ...userHistory.filter((user) => user !== value)].slice(0, 8);
    setUserHistory(next);
    window.localStorage.setItem(historyKey, JSON.stringify(next));
  }

  function closeApplication() {
    if (window.tpvDesktop) {
      void window.tpvDesktop.closeApplication();
      return;
    }
    window.close();
  }

  return (
    <main className="login-screen">
      <header className="entry-topbar">
        <strong className="app-brand-static">{t(app === "venta" ? "venta.title" : "gestion.title")}</strong>
      </header>
      <div className="login-store-heading">
        <strong>{terminalContext.storeName}</strong>
        <span>{t("login.terminalPrefix")}: {terminalContext.terminalCode}</span>
      </div>
      <button
        type="button"
        className="language-button"
        aria-expanded={languageOpen}
        aria-haspopup="listbox"
        aria-label={t("login.language")}
        title={t("login.language")}
        onClick={() => setLanguageOpen((open) => !open)}
      >
        <img alt="" src={languageIcon} />
      </button>
      {languageOpen && (
        <section className="language-picker" aria-label={t("login.language")}>
          {languageOptions.map((option) => (
            <button
              type="button"
              className={option.code === locale ? "selected" : ""}
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
      <button
        type="button"
        className="shutdown-button"
        aria-label={t("login.shutdown")}
        title={t("login.shutdown")}
        onClick={() => setShutdownOpen(true)}
      >
        ⏻
      </button>
      <form className="login-panel" onSubmit={submit}>
        <label>
          <span>{t("login.user")}</span>
          <input
            autoFocus
            list={`${app}-login-history`}
            value={username}
            disabled={loading}
            onChange={(event) => setUsername(event.target.value)}
            placeholder={t("login.userPlaceholder")}
          />
          <datalist id={`${app}-login-history`}>
            {userHistory.map((user) => (
              <option key={user} value={user} />
            ))}
          </datalist>
        </label>
        <label>
          <span>{t("login.password")}</span>
          <input
            value={password}
            disabled={loading}
            onChange={(event) => setPassword(event.target.value)}
            placeholder={t("login.passwordPlaceholder")}
            type="password"
          />
        </label>
        {error && <strong className="login-error">{error}</strong>}
        <button type="submit" disabled={loading}>{loading ? t("login.loading") : t("login.submit")}</button>
      </form>
      {shutdownOpen && (
        <div className="shutdown-overlay" role="dialog" aria-modal="true" aria-labelledby="shutdown-title">
          <section className="shutdown-dialog">
            <h2 id="shutdown-title">{t("login.shutdownConfirmTitle")}</h2>
            <p>{t("login.shutdownConfirmText")}</p>
            <div className="shutdown-actions">
              <button type="button" className="shutdown-no" autoFocus onClick={() => setShutdownOpen(false)}>
                {t("common.no")}
              </button>
              <button type="button" className="shutdown-yes" onClick={closeApplication}>
                {t("common.yes")}
              </button>
            </div>
          </section>
        </div>
      )}
      <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
    </main>
  );
}
