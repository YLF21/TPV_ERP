import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, setUnauthorizedHandler } from "../lib/api";
import type { Credentials, LoginCredentials } from "../lib/types";
import type { Notice } from "../shared/types";
import { I18nContext, readLanguage, translate, localeFor, setActiveLocale, type Language } from "../i18n";
import { LanguageSelector } from "../shared/ui";
import { errorMessage } from "../shared/lib";
import { TenantWorkspace } from "../features/tenant/TenantWorkspace";
import { RequiredPasswordChangeScreen } from "../features/auth/AuthScreens";

// Independent entry point: no administrative session or navigation is mounted here.
export function TenantApp() {
  const [language, setLanguage] = useState<Language>(() => readLanguage());
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [pending, setPending] = useState<{ credentials: Credentials; password: string } | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const generation = useRef(0);
  const current = useRef({ credentials, pending }); current.current = { credentials, pending };
  const i18n = useMemo(() => ({ language, setLanguage: (next: Language) => {
    localStorage.setItem("tpv-saas-language", next); setLanguage(next);
  }, t: (key: string) => translate(language, key) }), [language]);
  setActiveLocale(localeFor(language));
  const label = (es: string, en: string, zh: string) => language === "es" ? es : language === "zh" ? zh : en;
  const logout = useCallback(() => {
    generation.current++;
    const previous = current.current.credentials ?? current.current.pending?.credentials;
    current.current = { credentials: null, pending: null };
    setCredentials(null); setPending(null); setPassword(""); setBusy(false); setNotice(null);
    if (previous) void api.logout(previous).catch(() => undefined);
  }, []);
  useEffect(() => {
    setUnauthorizedHandler(failed => {
      if (failed.accessToken === (current.current.credentials ?? current.current.pending?.credentials)?.accessToken) logout();
    });
    return () => { generation.current++; setUnauthorizedHandler(null); };
  }, [logout]);

  async function login(input: LoginCredentials) {
    const id = ++generation.current; setBusy(true); setNotice(null);
    try {
      const result = await api.tenantLogin(input);
      if (id !== generation.current) { void api.logout(result).catch(() => undefined); return; }
      if (result.mode !== "tenant") {
        await api.logout(result);
        setNotice({ type: "error", text: label("Utiliza tu usuario de tienda. El acceso de administración está separado.", "Use your store account. Administration has a separate sign-in.", "请使用门店账号，管理员请从独立入口登录。") }); return;
      }
      const next: Credentials = { username: result.username, accessToken: result.accessToken, mode: result.mode };
      setPassword("");
      if (result.passwordChangeRequired) setPending({ credentials: next, password: input.password });
      else setCredentials(next);
    } catch (error) { if (id === generation.current) setNotice({ type: "error", text: errorMessage(error) }); }
    finally { if (id === generation.current) setBusy(false); }
  }
  async function changePassword(nextPassword: string, confirmation: string) {
    if (!pending || busy) return;
    if (nextPassword.length < 4 || nextPassword !== confirmation) { setNotice({ type: "error", text: i18n.t(nextPassword.length < 4 ? "passwordTooShort" : "passwordsDoNotMatch") }); return; }
    const id = ++generation.current; setBusy(true); setNotice(null);
    try {
      await api.changeOwnPassword(pending.credentials, { currentPassword: pending.password, newPassword: nextPassword });
      if (id !== generation.current) return;
      logout(); setNotice({ type: "success", text: i18n.t("passwordChanged") });
    } catch (error) { if (id === generation.current) setNotice({ type: "error", text: errorMessage(error) }); }
    finally { if (id === generation.current) setBusy(false); }
  }

  return <I18nContext.Provider value={i18n}>
    {credentials ? <TenantWorkspace credentials={credentials} onLogout={logout} onNotice={setNotice} />
      : pending ? <RequiredPasswordChangeScreen username={pending.credentials.username} loading={busy} notice={notice} onSubmit={changePassword} onCancel={logout} />
      : <main className="login-page">
        <header className="saas-login-topbar"><strong className="saas-login-brand">esPOS</strong><span>{label("Portal de tiendas", "Store portal", "门店门户")}</span><LanguageSelector /></header>
        <section className="login-panel" aria-label={label("Acceso de tienda", "Store sign-in", "门店登录")}>
          <header className="login-panel-heading"><strong>{label("Tu tienda, conectada", "Your connected store", "连接你的门店")}</strong><span>{label("Consulta tus incidencias y habla con soporte.", "Track your requests and contact support.", "查看事件并联系支持。")}</span></header>
          {notice && <p className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"}>{notice.text}</p>}
          <form className="stack-form" onSubmit={event => { event.preventDefault(); if (!busy) void login({ username: username.trim(), password }); }}>
            <label>{i18n.t("username")}<input autoComplete="username" value={username} onChange={event => setUsername(event.target.value)} required disabled={busy} /></label>
            <label>{i18n.t("password")}<input type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required disabled={busy} /></label>
            <button className="primary-button" type="submit" disabled={busy}>{i18n.t("enter")}</button>
          </form>
        </section>
      </main>}
  </I18nContext.Provider>;
}
