import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { SignOut } from "@phosphor-icons/react";
import esposWordmark from "../assets/espos-wordmark.png";
import languageIcon from "../assets/language.png";
import { api, ApiError, setUnauthorizedHandler } from "../lib/api";
import { isCurrentAuthRequest, isCurrentSessionRequest, shouldInvalidateSession } from "../lib/frontend-runtime.mjs";
import type { AdminSession, Credentials, LoginCredentials, DashboardData, FiscalStatusAdmin } from "../lib/types";
import { Language, readLanguage, localeFor, translate, I18nContext } from "../i18n/index";
import { View, Notice, GlobalSearchCriterion } from "../shared/types";
import { readViewFromLocation, buildGlobalSearchSuggestions, filterDashboardData, errorMessage, viewTitle } from "../shared/lib";
import { RequiredPasswordChangeScreen, LoginScreen, LoginClock } from "../features/auth/AuthScreens";
import { navigation, navigationGroups } from "./navigation";
import { availableView, isViewEnabled } from "./features";
import { NavigationIcon } from "./NavigationIcon";
import { AccountPassword } from "../shared/AccountPassword";
import { RefreshContext } from "./RefreshContext";
import { workspaceLabels } from "../i18n/workspace";
import { StoresView } from "../features/stores/StoresView";
import { LicenseWorkspace } from "../features/licenses/LicenseWorkspace";
import { CreateLicenseView } from "../features/licenses/CreateLicenseView";
import { ClientUsersView } from "../features/users/ClientUsersView";
import { SmtpSettingsView } from "../features/mail/SmtpSettingsView";
import { CommunicationsView } from "../features/communications/CommunicationsView";
import { FailuresView } from "../features/supervision/FailuresView";
import { LanguageSelector, NavButton, EmptyState } from "../shared/ui";
import { Dashboard } from "../features/dashboard/Dashboard";
import { CompaniesView } from "../features/companies/CompaniesView";
import { SyncView } from "../features/sync/SyncView";
import { FiscalStatusView, VerifactuPolicySection } from "../features/fiscal/FiscalViews";
import { UsersView } from "../features/users/UsersView";
import { SupportView } from "../features/support/SupportView";
import { CustomerHealthView } from "../features/health/CustomerHealthView";
import { BillingView } from "../features/billing/BillingView";
import { OutboxRecoveryView } from "../features/outbox/OutboxRecoveryView";
import { ReportsView } from "../features/reports/ReportsView";
import { ReportsWorkspace } from "../features/reports/ReportsWorkspace";
import { AuditView } from "../features/audit/AuditView";
import { DemoRequestsView } from "../features/marketing/DemoRequestsView";
import { setActiveLocale } from "../i18n";
import { appVersion } from "../lib/appVersion";

import type { SupportTarget } from "../features/support/support-labels";

function readAdminView(): View {
  const view = readViewFromLocation();
  return view === "access" ? "client-users" : view;
}

export default function App() {
  const [credentials, setCredentials] = useState<Credentials | null>(null);
  const [pendingPasswordChange, setPendingPasswordChange] = useState<{ credentials: Credentials; currentPassword: string } | null>(null);
  const [language, setLanguageState] = useState<Language>(() => readLanguage());
  setActiveLocale(localeFor(language));
  const [activeView, setActiveView] = useState<View>(readAdminView);
  const navigationGuard = useRef<(() => boolean) | null>(null);
  const activeViewRef = useRef(activeView);
  const [licenseDirectoryTitle, setLicenseDirectoryTitle] = useState("");
  const [supportTarget, setSupportTarget] = useState<SupportTarget | null>(null);
  const [returnFailureKey, setReturnFailureKey] = useState<string | undefined>();
  useEffect(() => { setSupportTarget(null); setReturnFailureKey(undefined); }, [credentials]);
  const [data, setData] = useState<DashboardData | null>(null);
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [session, setSession] = useState<AdminSession | null>(null);

  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<Notice>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchCriterion, setSearchCriterion] = useState<GlobalSearchCriterion>("company");
  const [searchFocused, setSearchFocused] = useState(false);
  const [searchStores, setSearchStores] = useState<FiscalStatusAdmin[]>([]);
  const [navigationQuery, setNavigationQuery] = useState("");
  const [expandedNavigationGroups, setExpandedNavigationGroups] = useState<Set<typeof navigationGroups[number]>>(() => new Set([navigation.find(item => item.view === readAdminView())?.group ?? "home"]));
  const navigationSearchRef = useRef<HTMLInputElement | null>(null);
  const navigationListRef = useRef<HTMLElement | null>(null);
  const refreshRequestId = useRef(0);
  const authRequestId = useRef(0);
  const credentialsRef = useRef<Credentials | null>(credentials);
  const pendingPasswordChangeRef = useRef<{ credentials: Credentials; currentPassword: string } | null>(pendingPasswordChange);
  credentialsRef.current = credentials;
  pendingPasswordChangeRef.current = pendingPasswordChange;
  const i18n = useMemo(
    () => ({
      language,
      setLanguage: (nextLanguage: Language) => {
        localStorage.setItem("tpv-saas-language", nextLanguage);
        setLanguageState(nextLanguage);
      },
      t: (key: string) => translate(language, key)
    }),
    [language]
  );
  const l = workspaceLabels(language);
  const title = (view: View) => viewTitle(view, key => ["companies", "stores", "activeLicenses", "createLicense", "failures", "access", "integrations"].includes(key) ? l(key as Parameters<typeof l>[0]) : i18n.t(key));
  const navigationItems = navigation.filter(item => isViewEnabled(item.view) && (!item.permission || session?.permissions.includes(item.permission))).map(item => ({ ...item, label: title(item.view) }));
  const activeNavigationItem = navigationItems.find(item => item.view === activeView);
  const visibleNavigationItems = navigationItems.filter(item => item.label.toLocaleLowerCase().includes(navigationQuery.trim().toLocaleLowerCase()));
  const searchSuggestions = useMemo(
    () => data ? buildGlobalSearchSuggestions(data, searchStores, searchCriterion, searchQuery) : [],
    [data, searchStores, searchCriterion, searchQuery]
  );
  const visibleData = useMemo(
    () => data ? filterDashboardData(data, searchQuery, searchCriterion, searchStores) : null,
    [data, searchQuery, searchCriterion, searchStores]
  );
  const permissions = useMemo(() => new Set(session?.permissions ?? []), [session]);
  const needsDashboardData = !["dashboard", "demo-requests", "reports", "audit", "users", "client-users", "communications", "smtp"].includes(activeView);

  function revealNavigationGroup(view: View) {
    const group = navigation.find(item => item.view === view)?.group;
    if (group) setExpandedNavigationGroups(previous => previous.has(group) ? previous : new Set([...previous, group]));
  }

  function openSupport(target: SupportTarget) { navigate("support"); setSupportTarget(target); }
  function returnToFailure(key: string) { navigate("failures"); setReturnFailureKey(key); }
  function navigate(view: View) {
    view = view === "access" ? "client-users" : availableView(view);
    if (view === activeView) return;
    if (navigationGuard.current && !navigationGuard.current()) return;
    setSupportTarget(null); setReturnFailureKey(undefined);
    activeViewRef.current = view;
    setActiveView(view);
    revealNavigationGroup(view);
    const nextHash = `#/${view}`;
    if (window.location.hash !== nextHash) window.history.pushState({ view }, "", nextHash);
  }

  useEffect(() => {
    refreshRequestId.current += 1;
    setLoading(false);
    revealNavigationGroup(activeView);
    setNotice(null);
  }, [activeView]);

  useEffect(() => {
    const syncView = () => {
      // Old public-site bookmarks now enter the independent administration app.
      if (!window.location.hash || /^#\/producto(?:\/|$)/.test(window.location.hash)) {
        window.history.replaceState({ entry: "login" }, "", "#/login");
      }
      const requested = window.location.hash.split("?")[0].replace(/^#\/?/, "").split("/")[0];
      if (readAdminView() !== activeViewRef.current && navigationGuard.current && !navigationGuard.current()) {
        const view = activeViewRef.current;
        window.history.pushState({ view }, "", `#/${view}`);
        return;
      }
      if (!isViewEnabled(requested)) window.history.replaceState({ view: "dashboard" }, "", "#/dashboard");
      activeViewRef.current = readAdminView();
      setActiveView(activeViewRef.current);
    };
    syncView();
    window.addEventListener("popstate", syncView);
    window.addEventListener("hashchange", syncView);
    return () => {
      window.removeEventListener("popstate", syncView);
      window.removeEventListener("hashchange", syncView);
    };
  }, []);
  useEffect(() => {
    setUnauthorizedHandler((failedCredentials) => {
      if (!shouldInvalidateSession(
        failedCredentials.accessToken,
        credentialsRef.current?.accessToken,
        pendingPasswordChangeRef.current?.credentials.accessToken
      )) return;
      refreshRequestId.current += 1;
      authRequestId.current += 1;
      credentialsRef.current = null;
      pendingPasswordChangeRef.current = null;
      setCredentials(null);
      setPendingPasswordChange(null);
      setData(null);

      setSession(null);
      setSearchStores([]);

      setLoading(false);
      setNotice({ type: "error", text: i18n.t("sessionExpired") });
    });
    return () => setUnauthorizedHandler(null);
  }, [i18n]);

  useEffect(() => {
    function focusNavigation(event: KeyboardEvent) {
      if (navigationSearchRef.current && (event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === "f") {
        event.preventDefault();
        navigationSearchRef.current.focus();
      }
    }
    window.addEventListener("keydown", focusNavigation);
    return () => window.removeEventListener("keydown", focusNavigation);
  }, []);
  useEffect(() => {
    if (credentials?.mode === "admin") {
      if (window.location.hash === "#/login") {
        window.history.replaceState({ view: activeView }, "", `#/${activeView}`);
      }
      if (!session || (needsDashboardData && !data)) void refresh(credentials);
    }
  }, [credentials, activeView]);

  async function refresh(activeCredentials = credentials, clearNotice = true): Promise<boolean> {
    if (!activeCredentials) return false;
    const requestId = ++refreshRequestId.current;
    const isCurrent = () => isCurrentSessionRequest(
      requestId,
      refreshRequestId.current,
      activeCredentials.accessToken,
      credentialsRef.current?.accessToken
    );
    setLoading(true);
    try {
        // Permission-gated independent modules must not depend on dashboard health.
        const nextSession = await api.session(activeCredentials);
        if (!isCurrent()) return false;
        setSession(nextSession);
        setRefreshVersion(v => v + 1);
        // These independent modules must not wait for legacy dashboard data.
        if (!needsDashboardData) {
          if (clearNotice) setNotice(null);
          return true;
        }
        const [dashboard, stores] = await Promise.all([
          api.dashboard(activeCredentials),
          api.fiscalStatus(activeCredentials).catch(() => [] as FiscalStatusAdmin[])
        ]);
        if (!isCurrent()) return false;
        setData(dashboard); setSearchStores(stores);
        if (clearNotice) setNotice(null);
        return true;
    } catch (error) {
      if (!isCurrent()) return false;
      if (error instanceof ApiError && error.status === 401) {
        credentialsRef.current = null;
        setCredentials(null);
      }
      setNotice({ type: "error", text: errorMessage(error) });
      return false;
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }
  function acceptInternalSession(next: Credentials) {
    if (next.mode === "admin") return true;
    void api.logout(next).catch(() => undefined);
    refreshRequestId.current += 1;
    credentialsRef.current = null;
    pendingPasswordChangeRef.current = null;
    setCredentials(null);
    setPendingPasswordChange(null);
    setData(null);
    setSession(null);
    setSearchStores([]);
    setNotice({ type: "error", text: i18n.t("internalAccessOnly") });
    return false;
  }

  async function login(nextCredentials: LoginCredentials) {
    const requestId = ++authRequestId.current;
    setLoading(true);
    setNotice(null);
    try {
      const authenticated = await api.login(nextCredentials);
      const next: Credentials = { username: authenticated.username, accessToken: authenticated.accessToken, mode: authenticated.mode };
      if (!isCurrentAuthRequest(requestId, authRequestId.current)) {
        void api.logout(next).catch(() => undefined);
        return;
      }
      if (!acceptInternalSession(next)) return;
      if (authenticated.passwordChangeRequired) {
        const pending = { credentials: next, currentPassword: nextCredentials.password };
        pendingPasswordChangeRef.current = pending;
        setPendingPasswordChange(pending);
        return;
      }
      credentialsRef.current = next;
      setCredentials(next);
    } catch (error) {
      if (isCurrentAuthRequest(requestId, authRequestId.current)) setNotice({ type: "error", text: errorMessage(error) });
    } finally {
      if (isCurrentAuthRequest(requestId, authRequestId.current)) setLoading(false);
    }
  }

  async function completeRequiredPasswordChange(newPassword: string, confirmation: string) {
    if (!pendingPasswordChange) return;
    if (newPassword.length < 4) { setNotice({ type: "error", text: i18n.t("passwordTooShort") }); return; }
    if (newPassword !== confirmation) { setNotice({ type: "error", text: i18n.t("passwordsDoNotMatch") }); return; }
    const pending = pendingPasswordChange;
    const requestId = ++authRequestId.current;
    setLoading(true); setNotice(null);
    try {
      await api.changeOwnPassword(pending.credentials, { currentPassword: pending.currentPassword, newPassword });
      if (!isCurrentAuthRequest(requestId, authRequestId.current)) return;
      const authenticated = await api.login({ username: pending.credentials.username, password: newPassword });
      const next: Credentials = { username: authenticated.username, accessToken: authenticated.accessToken, mode: authenticated.mode };
      if (!isCurrentAuthRequest(requestId, authRequestId.current)) {
        void api.logout(next).catch(() => undefined);
        return;
      }
      if (!acceptInternalSession(next)) return;
      pendingPasswordChangeRef.current = null;
      credentialsRef.current = next;
      setPendingPasswordChange(null);
      setNotice({ type: "success", text: i18n.t("passwordChanged") });
      setCredentials(next);
    } catch (error) { if (isCurrentAuthRequest(requestId, authRequestId.current)) setNotice({ type: "error", text: errorMessage(error) }); }
    finally { if (isCurrentAuthRequest(requestId, authRequestId.current)) setLoading(false); }
  }

  async function requestRecovery(username: string) {
    setLoading(true); setNotice(null);
    try { await api.requestPasswordRecovery(username.trim()); setNotice({ type: "success", text: i18n.t("recoveryGeneric") }); }
    catch (error) { setNotice({ type: "error", text: errorMessage(error) }); }
    finally { setLoading(false); }
  }

  async function confirmRecovery(token: string, newPassword: string, confirmation: string) {
    if (newPassword.length < 4) { setNotice({ type: "error", text: i18n.t("passwordTooShort") }); return; }
    if (newPassword !== confirmation) { setNotice({ type: "error", text: i18n.t("passwordsDoNotMatch") }); return; }
    setLoading(true); setNotice(null);
    try { await api.confirmPasswordRecovery({ token: token.trim(), newPassword }); setNotice({ type: "success", text: i18n.t("recoveryCompleted") }); }
    catch (error) { setNotice({ type: "error", text: errorMessage(error) }); }
    finally { setLoading(false); }
  }

  function logout() {
    if (navigationGuard.current && !navigationGuard.current()) return;
    const activeCredentials = credentialsRef.current ?? pendingPasswordChangeRef.current?.credentials;
    refreshRequestId.current += 1;
    authRequestId.current += 1;
    credentialsRef.current = null;
    pendingPasswordChangeRef.current = null;
    if (activeCredentials) void api.logout(activeCredentials).catch(() => undefined);
    setCredentials(null);
    setPendingPasswordChange(null);
    setData(null);

    setSession(null);
    setSearchStores([]);

    setNotice(null);
    setLoading(false);
    window.history.replaceState({ entry: "login" }, "", "#/login");
  }

  if (pendingPasswordChange) {
    return (
      <I18nContext.Provider value={i18n}>
        <RequiredPasswordChangeScreen username={pendingPasswordChange.credentials.username} loading={loading} notice={notice} onSubmit={completeRequiredPasswordChange} onCancel={logout} />
      </I18nContext.Provider>
    );
  }

  if (!credentials) {
    return (
      <I18nContext.Provider value={i18n}>
        <LoginScreen onLogin={login} onRequestRecovery={requestRecovery} onConfirmRecovery={confirmRecovery} loading={loading} notice={notice} />
      </I18nContext.Provider>
    );
  }

  return (
    <I18nContext.Provider value={i18n}>
    <RefreshContext.Provider value={refreshVersion}>
    <div className={`app-shell${["companies", "stores", "licenses", "audit", "communications"].includes(activeView) ? " app-shell--table-workspace" : ""}`}>
      <header className="app-system-bar">
        <div className="system-product">
          <img className="system-wordmark" src={esposWordmark} alt="esPOS" width={94} height={30} draggable={false} title={`${i18n.t("saasVersion")} ${appVersion}`} />
        </div>
        <LoginClock language={language} />
        <div className="system-session">
          <LanguageSelector variant="floating" iconSrc={languageIcon} />
          <AccountPassword credentials={credentials} onLogout={logout} userLabel={session?.username ?? credentials.username} />
          <button className="session-logout" type="button" onClick={logout} aria-label={i18n.t("logout")} title={i18n.t("logout")}>
            <SignOut size={19} aria-hidden="true" />
          </button>
        </div>
      </header>

      <aside className="app-header" aria-label={i18n.t("mainNavigation")}>
        <div className="brand">
          <strong>{i18n.t("centralServer")}</strong>
        </div>
        <div className="saas-nav-search">
          <svg className="sidebar-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false"><circle cx="10" cy="10" r="6" /><path d="m15 15 5 5" /></svg>
          <input ref={navigationSearchRef} type="search" value={navigationQuery} placeholder={l("searchModules")} aria-label={l("searchModules")} onChange={(event) => setNavigationQuery(event.target.value)} onKeyDown={(event) => {
            if (event.key === "Escape") { setNavigationQuery(""); event.currentTarget.blur(); }
            if (["Enter", "ArrowDown", "ArrowUp"].includes(event.key)) {
              event.preventDefault();
              const results = [...(navigationListRef.current?.querySelectorAll<HTMLButtonElement>(".nav-button") ?? [])].filter(button => button.getClientRects().length);
              if (event.key === "Enter") results[0]?.click();
              else (event.key === "ArrowUp" ? results.at(-1) : results[0])?.focus();
            }
          }} />
          <kbd aria-hidden="true">Ctrl F</kbd>
        </div>
        <nav ref={navigationListRef} className="nav-list top-nav-list" aria-label={i18n.t("mainNavigation")} onKeyDown={event => {
          if (!navigationQuery.trim() || !(event.target instanceof HTMLButtonElement)) return;
          if (event.key === "Escape") { event.preventDefault(); setNavigationQuery(""); navigationSearchRef.current?.focus(); }
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            const results = [...event.currentTarget.querySelectorAll<HTMLButtonElement>(".nav-button")].filter(button => button.getClientRects().length);
            const index = results.indexOf(event.target);
            if (index < 0 || !results.length) return;
            event.preventDefault();
            results[(index + (event.key === "ArrowDown" ? 1 : results.length - 1)) % results.length]?.focus();
          }
        }}>
          {navigationGroups.map(group => {
            const items = visibleNavigationItems.filter(item => item.group === group);
            const expanded = Boolean(navigationQuery.trim()) || expandedNavigationGroups.has(group);
            const current = activeNavigationItem?.group === group;
            return items.length > 0 && <div className="nav-group" key={group}>
              <button className={"nav-group-toggle" + (current ? " nav-group-toggle--current" : "")} type="button"
                aria-expanded={expanded} aria-controls={"nav-group-" + group} disabled={Boolean(navigationQuery.trim())}
                onClick={() => setExpandedNavigationGroups(previous => {
                  const next = new Set(previous);
                  if (next.has(group)) next.delete(group); else next.add(group);
                  return next;
                })}>
                <span className="nav-group-title">{l(group)}</span>
                <svg className="nav-group-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true" focusable="false"><path d="m9 5 7 7-7 7" /></svg>
              </button>
              <div id={"nav-group-" + group} className="nav-group-items" hidden={!expanded}>
                {items.map((item, index) => <Fragment key={item.view}>
                  {group === "system" && item.phase && item.phase !== items[index - 1]?.phase && <span className="nav-phase-title">{l(item.phase)}</span>}
                  <NavButton active={activeView === item.view} onClick={() => { navigate(item.view); setNavigationQuery(""); }} label={item.label} icon={<NavigationIcon view={item.view} />} />
                </Fragment>)}
              </div>
            </div>;
          })}
          {visibleNavigationItems.length === 0 && <p className="saas-nav-empty">{i18n.t("noEventsForFilter")}</p>}
        </nav>
        <footer className="app-context-footer" aria-label={l("platform")}>
          <div className="sidebar-account-icon" aria-hidden="true"><NavigationIcon view="users" /></div>
          <div className="sidebar-account-copy"><strong>{session?.username ?? credentials.username}</strong>
          <span>{title(activeView)}</span></div>
        </footer>
      </aside>

      <main className="main-panel">
        <header className="topbar">
          <div>
            <p className="eyebrow">{activeNavigationItem ? l(activeNavigationItem.phase ?? activeNavigationItem.group) : i18n.t("centralPanel")}</p>
            <h1>{activeView === "licenses" && licenseDirectoryTitle ? licenseDirectoryTitle : title(activeView)}</h1>
            {activeNavigationItem?.description && <p className="module-help">{l(activeNavigationItem.description)}</p>}
          </div>
          {!["smtp", "communications"].includes(activeView) && <button className="secondary-button" type="button" onClick={() => void refresh()} disabled={loading}>
            {loading ? i18n.t("refreshing") : i18n.t("refresh")}
          </button>}
        </header>

        {data && ["health", "billing", "support"].includes(activeView) && (
          <div
            className="global-search guided-global-search"
            role="search"
            onFocusCapture={() => setSearchFocused(true)}
            onBlurCapture={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setSearchFocused(false);
            }}
          >
            <label className="global-search-criterion">
              <span>{i18n.t("searchBy")}</span>
              <select
                value={searchCriterion}
                onChange={(event) => {
                  setSearchCriterion(event.target.value as GlobalSearchCriterion);
                  setSearchQuery("");
                }}
              >
                <option value="company">{i18n.t("searchCompany")}</option>
                <option value="store">{i18n.t("searchStore")}</option>
                <option value="taxId">{i18n.t("searchTaxId")}</option>
              </select>
            </label>
            <div className="global-search-value">
              <label>
                <span>{i18n.t("searchValue")}</span>
                <input
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  placeholder={i18n.t("globalSearch")}
                  aria-label={i18n.t("globalSearch")}
                  aria-autocomplete="list"
                  aria-controls="global-search-suggestions"
                  aria-expanded={searchFocused && searchSuggestions.length > 0}
                />
              </label>
              {searchFocused && searchQuery.trim() && searchSuggestions.length > 0 && (
                <div id="global-search-suggestions" className="global-search-suggestions" role="listbox" aria-label={i18n.t("matchingSuggestions")}>
                  {searchSuggestions.map((suggestion) => (
                    <button
                      key={suggestion.key}
                      type="button"
                      role="option"
                      aria-selected="false"
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => {
                        setSearchQuery(suggestion.value);
                        setSearchFocused(false);
                      }}
                    >
                      <strong>{suggestion.label}</strong>
                      <span>{suggestion.detail}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            {searchQuery && (
              <button className="small-button" type="button" onClick={() => setSearchQuery("")}>
                {i18n.t("clearSearch")}
              </button>
            )}
          </div>
        )}

        {notice && <div className={`notice ${notice.type}`} role={notice.type === "error" ? "alert" : "status"} aria-live={notice.type === "error" ? "assertive" : "polite"}>{notice.text}</div>}

        {activeView === "dashboard" ? (
          session ? <Dashboard credentials={credentials} permissions={permissions} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "reports" ? (
          session ? <ReportsWorkspace credentials={credentials} permissions={permissions} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "demo-requests" ? (
          session ? <DemoRequestsView credentials={credentials} permissions={permissions} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "audit" ? (
          session
            ? permissions.has("VIEW_ADMIN_DATA")
              ? <AuditView credentials={credentials} />
              : <EmptyState text={i18n.t("auditReadForbidden")} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "users" ? (
          session ? <UsersView credentials={credentials} permissions={permissions} onNotice={setNotice} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "client-users" ? (
          session && permissions.has("MANAGE_TENANT_USERS")
            ? <ClientUsersView credentials={credentials} onNotice={setNotice} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "communications" ? (
          session ? <CommunicationsView key={credentials.accessToken} credentials={credentials} navigationGuard={navigationGuard} onOpenSmtp={() => navigate("smtp")} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : activeView === "smtp" ? (
          session ? <SmtpSettingsView key={credentials.accessToken} credentials={credentials} navigationGuard={navigationGuard} />
            : <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : !visibleData ? (
          <EmptyState text={loading ? i18n.t("loadingSaas") : i18n.t("noLoadedData")} />
        ) : (
          <>
            {activeView === "stores" && <StoresView credentials={credentials} permissions={permissions} onNotice={setNotice} />}
            {activeView === "licenses" && <LicenseWorkspace credentials={credentials} installations={data!.installations} permissions={permissions} onChanged={() => refresh()} onTitleChange={setLicenseDirectoryTitle} />}
            {activeView === "create-license" && <CreateLicenseView credentials={credentials} permissions={permissions} onChanged={() => refresh()} />}
            {activeView === "failures" && <FailuresView credentials={credentials} licenses={data!.licenses} permissions={permissions} onNavigate={navigate} onNotice={setNotice} initialFailureKey={returnFailureKey} onOpenSupport={openSupport} />}
            {activeView === "companies" && (
              <CompaniesView
                credentials={credentials}
                installations={data!.installations}
                permissions={permissions}
                onChanged={() => void refresh()}
                onNotice={setNotice}
              />
            )}
            {activeView === "sync" && (
              <SyncView
                credentials={credentials}
                licenses={data!.licenses}
                permissions={permissions}
                onNotice={setNotice}
              />
            )}
            {activeView === "fiscal" && <FiscalStatusView credentials={credentials} licenses={data!.licenses} onNotice={setNotice} />}
            {activeView === "fiscal-policy" && <VerifactuPolicySection credentials={credentials} canManage={permissions.has("MANAGE_FISCAL_POLICY")} onChanged={() => void refresh()} onNotice={setNotice} />}
            {activeView === "support" && (
              <SupportView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} target={supportTarget} onReturnToFailure={returnToFailure} onClearTarget={() => setSupportTarget(null)} />
            )}
            {activeView === "health" && (
              <CustomerHealthView credentials={credentials} licenses={visibleData.licenses} onNotice={setNotice} />
            )}
            {activeView === "billing" && (
              <BillingView credentials={credentials} licenses={visibleData.licenses} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "outbox" && (
              <OutboxRecoveryView credentials={credentials} permissions={permissions} onNotice={setNotice} />
            )}
            {activeView === "integrations" && (
              <ReportsView mode="integrations" credentials={credentials} licenses={data!.licenses} permissions={permissions} onNotice={setNotice} />
            )}
          </>
        )}
      </main>

    </div>
    </RefreshContext.Provider>
    </I18nContext.Provider>
  );
}
