import { WindowCloseButton } from "./WindowCloseButton";
import { FormEvent, useEffect, useId, useMemo, useRef, useState } from "react";
import { ApiConnectionError, ApiError, checkBackendConnection } from "../api/client";
import { authenticateRemote } from "../auth/auth";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { TopDateTime } from "./TopDateTime";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import languageIcon from "../assets/language.png";
import { AppBrand } from "./AppBrand";
import { Eye, EyeSlash, LockKey } from "@phosphor-icons/react";
import { LoginUsernameHistory } from "./LoginUsernameHistory";
import { TouchAlphaKeyboard } from "./TouchAlphaKeyboard";
import { TouchKeyboardToggle } from "./TouchKeyboardToggle";
import { TouchKeyboardClearButton } from "./TouchKeyboardClearButton";

type LoginScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  terminalContext: TerminalContext;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogin: (session: UserSession) => void;
  onAuthenticationError?: (error: unknown) => void;
  presentation?: "desktop" | "embedded";
  heading?: string;
  notice?: string;
  secondaryActionLabel?: string;
  onSecondaryAction?: () => void;
  onConfigureConnection?: () => void;
  connectionUnavailable?: boolean;
  onReconnect?: () => Promise<boolean>;
};

const languageOptions: Array<{ code: LocaleCode; label: string }> = [
  { code: "es", label: "Español" },
  { code: "en", label: "English" },
  { code: "zh", label: "中文" }
];

export function LoginScreen({
  app,
  locale,
  terminalContext,
  onLocaleChange,
  onLogin,
  onAuthenticationError,
  presentation = "desktop",
  heading,
  notice,
  secondaryActionLabel,
  onSecondaryAction,
  onConfigureConnection,
  connectionUnavailable = false,
  onReconnect
}: LoginScreenProps) {
  const t = createTranslator(locale);
  const desktopChrome = presentation === "desktop";
  const saleLogin = desktopChrome && app === "venta";
  const terminalName = terminalContext.terminalName || terminalContext.terminalCode;
  const screenHeading = heading ?? t(app === "venta" ? "venta.title" : "gestion.title");
  const historyKey = useMemo(() => `tpverp.${app}.loginUsers`, [app]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [userHistory, setUserHistory] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [backendOnline, setBackendOnline] = useState<boolean | null>(connectionUnavailable ? false : null);
  const [languageOpen, setLanguageOpen] = useState(false);
  const [shutdownOpen, setShutdownOpen] = useState(false);
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const [keyboardField, setKeyboardField] = useState<"username" | "password">("username");
  const keyboardId = useId();
  const formRef = useRef<HTMLFormElement | null>(null);
  const keyboardInputRef = useRef<HTMLInputElement | null>(null);
  const languagePickerRef = useRef<HTMLDivElement | null>(null);
  const passwordInputRef = useRef<HTMLInputElement | null>(null);

  useOutsidePointerDown(languageOpen, languagePickerRef, () => setLanguageOpen(false));

  useEffect(() => {
    try {
      const stored = window.sessionStorage.getItem(historyKey);
      setUserHistory(stored ? JSON.parse(stored) : []);
    } catch {
      setUserHistory([]);
    }
  }, [historyKey]);

  useEffect(() => {
    let cancelled = false;
    if (connectionUnavailable) {
      setBackendOnline(false);
      return;
    }
    setBackendOnline(null);
    void checkBackendConnection().then((online) => {
      if (!cancelled) {
        setBackendOnline(online);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [connectionUnavailable]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (connectionUnavailable || backendOnline !== true) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const normalizedUsername = username.trim();
      const session = await authenticateRemote(normalizedUsername, password, app, terminalContext);
      rememberUser(normalizedUsername);
      onLogin(session);
    } catch (caught) {
      onAuthenticationError?.(caught);
      const invalidCredentials = caught instanceof ApiError && caught.status === 401;
      if (caught instanceof ApiConnectionError) {
        setBackendOnline(false);
      }
      const message =
        caught instanceof Error && caught.message === "no_access"
          ? t("login.noAccess")
          : caught instanceof Error && caught.message === "terminal_not_configured"
            ? t("login.terminalMissing")
            : caught instanceof ApiError && caught.status === 401
              ? t("login.invalid")
              : t("login.connectionError");
      setError(message);
      if (invalidCredentials) {
        setPassword("");
        setPasswordVisible(false);
        window.requestAnimationFrame(() => passwordInputRef.current?.focus());
      }
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
    window.sessionStorage.setItem(historyKey, JSON.stringify(next));
  }

  async function retryBackendConnection() {
    setBackendOnline(null);
    setError(null);
    const online = await (onReconnect ? onReconnect() : checkBackendConnection());
    setBackendOnline(online);
  }

  function closeApplication() {
    if (window.tpvDesktop) {
      void window.tpvDesktop.closeApplication();
      return;
    }
    window.close();
  }

  return (
    <main className={`login-screen login-screen-${presentation}${saleLogin ? " login-sale" : ""}${saleLogin && keyboardOpen ? " login-keyboard-open" : ""}`} data-app={app}>
      {desktopChrome && (
        <>
          <header className="entry-topbar">
            {saleLogin ? <div className="login-context-heading">
              <strong>{terminalContext.companyName || "—"}</strong>
              <strong>{terminalContext.storeName}</strong>
              <span>{terminalName}</span>
            </div> : <strong className="app-brand-static"><AppBrand app={app} label={screenHeading} /></strong>}
          </header>
          <TopDateTime locale={locale} />
          {!saleLogin && <div className="login-store-heading">
            <strong>{terminalContext.storeName}</strong>
            <span>{t("login.terminalPrefix")}: {terminalContext.terminalCode}</span>
          </div>}
          <div ref={languagePickerRef} style={{ display: "contents" }}>
            <button type="button" className="language-button" aria-expanded={languageOpen} aria-haspopup="listbox" aria-label={t("login.language")} title={t("login.language")} onClick={() => setLanguageOpen((open) => !open)}>
              <img alt="" src={languageIcon} />
            </button>
            {languageOpen && (
              <section className="language-picker" aria-label={t("login.language")}>
                {languageOptions.map((option) => (
                  <button type="button" className={option.code === locale ? "selected" : ""} key={option.code} onClick={() => { onLocaleChange(option.code); setLanguageOpen(false); }}>
                    <span>{option.label}</span><strong>{option.code.toUpperCase()}</strong>
                  </button>
                ))}
              </section>
            )}
          </div>
          <button type="button" className="shutdown-button" aria-label={t("login.shutdown")} title={t("login.shutdown")} onClick={() => setShutdownOpen(true)}>⏻</button>
        </>
      )}
      <div className={saleLogin ? "login-content" : undefined}>
      <div className={saleLogin ? "login-entry" : undefined}>
      {saleLogin && <div className="login-sale-brand"><AppBrand app="venta" label={screenHeading} /><span>{t("login.salesBrand")}</span></div>}
      <form className="login-panel" ref={formRef} onSubmit={submit} onFocusCapture={(event) => {
        const input = event.target;
        if (input instanceof HTMLInputElement && (input.id === `${app}-login-user` || input.id === `${app}-login-password`)) {
          keyboardInputRef.current = input;
          setKeyboardField(input.id === `${app}-login-password` ? "password" : "username");
        }
      }}>
        <header className="login-panel-heading">
          {!saleLogin && <strong>{screenHeading}</strong>}
          <span>{saleLogin ? `${terminalContext.storeName} · ${terminalName}` : `${terminalContext.storeName} - ${t("login.terminalPrefix")} ${terminalContext.terminalCode}`}</span>
        </header>
        {notice && <p className="login-inline-notice">{notice}</p>}
        <div className={saleLogin ? "login-field" : undefined} style={saleLogin ? undefined : { display: "grid", gap: 5 }}>
          <label htmlFor={`${app}-login-user`}>{t("login.user")}</label>
          {saleLogin ? <LoginUsernameHistory id={`${app}-login-user`} value={username}
            history={userHistory} placeholder={t("login.salesUserPlaceholder")} historyLabel={t("login.userHistory")}
            disabled={loading} onChange={setUsername} /> : <div>
          <input
            id={`${app}-login-user`}
            style={saleLogin ? undefined : { width: "100%" }}
            autoFocus
            autoComplete="username"
            list={`${app}-login-history`}
            value={username}
            disabled={loading}
            onChange={(event) => setUsername(event.target.value)}
            placeholder={t(saleLogin ? "login.salesUserPlaceholder" : "login.userPlaceholder")}
          />
          </div>}
          {!saleLogin && <datalist id={`${app}-login-history`}>
            {userHistory.map((user) => (
              <option key={user} value={user} />
            ))}
          </datalist>}
        </div>
        <div className={saleLogin ? "login-field" : undefined} style={saleLogin ? undefined : { display: "grid", gap: 5 }}>
          <label htmlFor={`${app}-login-password`}>{t("login.password")}</label>
          <div className={saleLogin ? "login-input-wrap" : undefined}>
          {saleLogin && <LockKey size={30} weight="fill" aria-hidden="true" />}
          <input
            id={`${app}-login-password`}
            style={saleLogin ? undefined : { width: "100%" }}
            ref={passwordInputRef}
            autoComplete="current-password"
            value={password}
            disabled={loading}
            onChange={(event) => setPassword(event.target.value)}
            placeholder={saleLogin ? "****" : t("login.passwordPlaceholder")}
            type={saleLogin && passwordVisible ? "text" : "password"}
          />
          {saleLogin && <button type="button" className="login-password-toggle"
            aria-label={t(passwordVisible ? "login.hidePassword" : "login.showPassword")}
            aria-pressed={passwordVisible} aria-controls={`${app}-login-password`}
            disabled={loading} onMouseDown={(event) => event.preventDefault()}
            onClick={() => setPasswordVisible((visible) => !visible)}>
            {passwordVisible ? <EyeSlash size={34} aria-hidden="true" /> : <Eye size={34} aria-hidden="true" />}
          </button>}
          </div>
        </div>
        {error && <strong className="login-error">{error}</strong>}
        {saleLogin && <div className="login-keyboard-button-row" onPointerDown={(event) => event.preventDefault()}>
          {keyboardOpen && <TouchKeyboardClearButton locale={locale} disabled={loading || shutdownOpen} onClick={() => {
            if (keyboardField === "password") setPassword("");
            else setUsername("");
            keyboardInputRef.current?.focus({ preventScroll: true });
          }} />}
          <TouchKeyboardToggle expanded={keyboardOpen} controls={keyboardId} disabled={loading || shutdownOpen}
            openLabel={t("sale.touch.keyboard.open")} closeLabel={t("sale.touch.keyboard.close")}
            onClick={() => {
              if (!keyboardInputRef.current) {
                keyboardInputRef.current = formRef.current?.querySelector<HTMLInputElement>(`#${app}-login-user`) ?? null;
              }
              setKeyboardOpen((open) => !open);
              keyboardInputRef.current?.focus({ preventScroll: true });
            }} />
        </div>}
        <button
          type="submit"
          className="login-submit"
          aria-describedby="login-server-status"
          disabled={loading || connectionUnavailable || backendOnline !== true}
        >
          {loading ? t("login.loading") : t("login.submit")}
        </button>
        <span
          id="login-server-status"
          className={`login-server-status${saleLogin && backendOnline === true ? " login-status-hidden" : ""} ${
            backendOnline === false ? "offline" : backendOnline === null ? "checking" : "online"
          }`}
          role={backendOnline === false ? "alert" : "status"}
        >
          {backendOnline === null
            ? t(saleLogin ? "login.localChecking" : "login.backendChecking")
            : backendOnline
              ? t("login.backendOnline")
              : t(saleLogin ? "login.localOffline" : "login.backendOffline")}
        </span>
        {app === "venta" && backendOnline === false && onConfigureConnection && <button type="button" className="login-secondary-action"
          onClick={onConfigureConnection}>{t("terminalLink.title")}</button>}
        {backendOnline === false && (
          <button
            type="button"
            className="login-retry-button"
            onClick={() => void retryBackendConnection()}
          >
            {t("login.backendRetry")}
          </button>
        )}
        {secondaryActionLabel && onSecondaryAction && (
          <button
            type="button"
            className="login-secondary-action"
            onClick={onSecondaryAction}
          >
            {secondaryActionLabel}
          </button>
        )}
      </form>
      </div>
      </div>
      {saleLogin && <section className="login-touch-keyboard" aria-label={t("sale.touch.keyboard.title")}>
        <div id={keyboardId} hidden={!keyboardOpen}>
          <TouchAlphaKeyboard locale={locale} collapsible={false} hideClearButton inputRef={keyboardInputRef}
            value={keyboardField === "password" ? password : username}
            onChange={keyboardField === "password" ? setPassword : setUsername}
            disabled={!keyboardOpen || loading || shutdownOpen} />
        </div>
      </section>}
      {shutdownOpen && (
        <div className="shutdown-overlay" role="dialog" aria-modal="true" aria-labelledby="shutdown-title">
          <section className="shutdown-dialog">
            <h2 id="shutdown-title" className="erp-window-header" aria-label={t("login.shutdownConfirmTitle")}>{t("login.shutdownConfirmTitle")}<WindowCloseButton type="button" aria-label={t("common.close")} onClick={() => setShutdownOpen(false)} desktopOnly /></h2>
            <p>{t("login.shutdownConfirmText")}</p>
            <div className="shutdown-actions erp-dialog-actions-row">
              <button type="button" className="shutdown-no erp-dialog-action-cancel erp-dialog-dismiss" autoFocus onClick={() => setShutdownOpen(false)}>
                {t("common.no")}
              </button>
              <button type="button" className="shutdown-yes erp-dialog-action-confirm" onClick={closeApplication}>
                {t("common.yes")}
              </button>
            </div>
          </section>
        </div>
      )}
      {desktopChrome && app !== "gestion" && <ScreenContextFooter locale={locale} terminalContext={terminalContext} terminalNameOnly={saleLogin} />}
    </main>
  );
}
