import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowClockwise, CalendarBlank, CaretDown, ChartBar, Circle, DotsThree, MagnifyingGlass, Tag } from "@phosphor-icons/react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { PromotionWizard, promotionProductsPath } from "./PromotionWizard";
import type { PromotionView } from "./PromotionWizard";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { ErpFilterChips } from "./ErpFilterChips";
import { ErpSelect } from "./ErpSelect";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import { formatPromotionDateRange, promotionTypeLabel, promotionConditionSentences, promotionDateRange, type PromotionTranslator } from "./promotionPresentation";
export { promotionTypeLabel, promotionConditionSentences, promotionDateRange } from "./promotionPresentation";
import "./PromotionListScreen.css";

type PromotionListScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  embedded?: boolean;
};

type PromotionAction = "duplicate" | "activate" | "deactivate" | "delete";
export type PromotionListColumnKey = "name" | "status" | "type" | "date" | "segment";

export const promotionListTableKey = "promotions.list";
export const promotionListColumnDefinitions = [
  { key: "name", defaultWidth: 220 },
  { key: "status", defaultWidth: 100 },
  { key: "type", defaultWidth: 180 },
  { key: "date", defaultWidth: 190 },
  { key: "segment", defaultWidth: 150 }
] as const satisfies readonly TableColumnDefinition<PromotionListColumnKey>[];

export function promotionActionRequest(action: PromotionAction, promotionId: string) {
  const basePath = `/promotions/${encodeURIComponent(promotionId)}`;
  return action === "delete"
    ? { path: basePath, method: "DELETE" as const }
    : { path: `${basePath}/${action}`, method: "POST" as const };
}

export function promotionActionDisabled(action: PromotionAction, promotion: PromotionView) {
  if (action === "activate") {
    return promotion.status === "ACTIVE";
  }
  if (action === "deactivate") {
    return promotion.status !== "ACTIVE";
  }
  if (action === "delete") {
    return promotion.status === "ACTIVE" || promotion.used === true;
  }
  return false;
}

export function PromotionListScreen({
  app,
  locale,
  session,
  terminalContext,
  onBack,
  onLocaleChange,
  onLogout,
  embedded = false
}: PromotionListScreenProps) {
  const t = createTranslator(locale);
  const [promotions, setPromotions] = useState<PromotionView[]>([]);
  const [products, setProducts] = useState<PromotionCatalogProduct[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [productsLoading, setProductsLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [pendingAction, setPendingAction] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [createOpen, setCreateOpen] = useState(false);
  const [actionsOpen, setActionsOpen] = useState(false);
  const actionsRef = useRef<HTMLDivElement>(null);
  const actionsButtonRef = useRef<HTMLButtonElement>(null);
  useOutsidePointerDown(actionsOpen, actionsRef, () => setActionsOpen(false));
  const searchRef = useRef<HTMLInputElement>(null);
  const token = session.accessToken;
  const today = localIsoDate();
  const filteredPromotions = useMemo(() => {
    const translate = createTranslator(locale);
    const normalized = (value: string) => value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase(locale).trim();
    const criterion = normalized(query);
    return promotions.filter(promotion => (statusFilter === "ALL" || (statusFilter === "EXPIRED"
      ? isPromotionExpired(promotion, today) : !isPromotionExpired(promotion, today) && promotion.status === statusFilter)) && (!criterion || [promotion.name,
      translate(`promotion.status.${promotion.status}`), translate(`promotion.type.${promotion.type}`),
      promotionDateRange(promotion), translate(`promotion.segment.${promotion.customerSegment ?? "ALL"}`)]
      .some(value => normalized(value).includes(criterion))));
  }, [locale, promotions, query, statusFilter, today]);
  const activePromotions = filteredPromotions.filter(promotion => !isPromotionExpired(promotion, today));
  const expiredPromotions = filteredPromotions.filter(promotion => isPromotionExpired(promotion, today));

  const selectedPromotion = useMemo(
    () => promotions.find((promotion) => promotion.id === selectedId) ?? null,
    [promotions, selectedId]
  );
  const selectedProducts = useMemo(() => {
    if (!selectedPromotion) return [];
    return products.filter(product => ((selectedPromotion.scope ?? "SALE") !== "SALE" || product.active !== false)
      && promotionMatchesProduct(selectedPromotion, product))
      .sort((left, right) => (left.code || left.name || left.id).localeCompare(right.code || right.name || right.id, locale));
  }, [locale, products, selectedPromotion]);
  const currency = useMemo(() => new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }), [locale]);
  const number = useMemo(() => new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }), [locale]);
  const conditions = selectedPromotion ? promotionConditionSentences(selectedPromotion, t, locale) : [];

  useEffect(() => { setActionsOpen(false); }, [selectedId]);

  async function loadPromotions() {
    try {
      setLoading(true);
      const rows = await apiRequest<PromotionView[]>("/promotions", { token });
      setPromotions(rows);
      setStatus(rows.length === 0 ? t("promotion.list.empty") : t("promotion.status.loaded"));
      setSelectedId((current) => rows.some((promotion) => promotion.id === current)
        ? current
        : rows.find(promotion => !isPromotionExpired(promotion, today))?.id ?? rows[0]?.id ?? null);
    } catch {
      setStatus(t("promotion.status.loadError"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadPromotions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, locale]);

  useEffect(() => {
    let active = true;
    setProductsLoading(true);
    void apiRequest<PromotionCatalogProduct[]>(promotionProductsPath, { token })
      .then(rows => { if (active) setProducts(Array.isArray(rows) ? rows : []); })
      .catch(() => { if (active) setProducts([]); })
      .finally(() => { if (active) setProductsLoading(false); });
    return () => { active = false; };
  }, [token]);

  async function runAction(action: PromotionAction, promotion: PromotionView) {
    if (promotionActionDisabled(action, promotion)) {
      return;
    }
    const request = promotionActionRequest(action, promotion.id);
    try {
      setPendingAction(`${promotion.id}:${action}`);
      await apiRequest<void | PromotionView>(request.path, { token, method: request.method });
      await loadPromotions();
    } catch {
      setStatus(t(`promotion.status.${action}Error`));
    } finally {
      setPendingAction("");
    }
  }

  return (
    <main className={`promotion-screen${embedded ? " gestion-embedded-module" : ""}`}>
      {!embedded && <SessionTopControls
        locale={locale} session={session} languageLabel={t("login.language")}
        shutdownLabel={t("login.shutdown")} changePasswordLabel={t("common.changePassword")}
        logoutLabel={t("common.logout")} shutdownConfirmTitle={t("login.shutdownConfirmTitle")}
        shutdownConfirmText={t("login.shutdownConfirmText")} noLabel={t("common.no")} yesLabel={t("common.yes")}
        onLocaleChange={onLocaleChange} onLogout={onLogout}
      />}

      {createOpen && app === "gestion" ? <div className="promotion-create-page">
        <PromotionWizard
          locale={locale} session={session} onClose={() => setCreateOpen(false)}
          onCreated={(promotion) => {
            setPromotions((current) => [promotion, ...current]);
            setSelectedId(promotion.id);
            setStatus(t("promotion.status.created"));
            setCreateOpen(false);
          }}
        />
      </div> : <section className="promotion-shell" aria-label={t("promotion.list.title")}>
        <header className="promotion-page-heading">
          <div>
            {!embedded && <button type="button" className="promotion-back" onClick={onBack}>
              {t(app === "venta" ? "venta.title" : "gestion.title")}
            </button>}
            <span className="promotion-eyebrow">{t("promotion.list.title")}</span>
            <h1>{t("promotion.list.heading")}</h1>
            <p>{t("promotion.list.subtitle")}</p>
          </div>
          {app === "gestion" && <button type="button" className="promotion-primary-button" onClick={() => setCreateOpen(true)}>
            {t("promotion.action.create")}
          </button>}
        </header>

        <section className="promotion-list-panel" aria-label={t("promotion.list.heading")}>
          <div className="promotion-list-filters">
            <label className="promotion-search-field">
              <input ref={searchRef} aria-label={t("salesReport.search")} type="search"
                placeholder={t("promotion.list.search")} value={query} onChange={event => setQuery(event.target.value)} />
              <MagnifyingGlass size={17} aria-hidden="true" />
            </label>
            <div className="promotion-status-filter"><ErpSelect value={statusFilter} aria-label={t("promotion.column.status")}
              options={[
                { value: "ALL", label: t("promotion.filter.all") },
                ...(["ACTIVE", "DRAFT", "INACTIVE"] as const).map(value => ({ value, label: t(`promotion.status.${value}`) })),
                { value: "EXPIRED", label: t("promotion.list.expired") }
              ]}
              onChange={setStatusFilter}
            /><CaretDown size={13} aria-hidden="true" /></div>
            <ErpFilterChips locale={locale} focusRef={searchRef} chips={query.trim() ? [{
              key: "search", label: t("salesReport.search"), value: query.trim(), onRemove: () => setQuery("")
            }] : []} onClear={() => setQuery("")} />
          </div>
          <div className="promotion-list-scroll" aria-busy={loading}>
            <PromotionGroup promotions={activePromotions} selectedId={selectedId} locale={locale} t={t} onSelect={setSelectedId} expired={false} />
            <PromotionGroup promotions={expiredPromotions} selectedId={selectedId} locale={locale} t={t} onSelect={setSelectedId} expired />
            {!loading && filteredPromotions.length === 0 && (
              <p className="promotion-empty">{promotions.length ? t("stock.status.noResults") : t("promotion.list.empty")}</p>
            )}
          </div>
          <footer className="promotion-list-footer">
            <span role="status">{loading ? t("promotion.status.loading") : `${number.format(filteredPromotions.length)} ${t("promotion.list.heading").toLocaleLowerCase(locale)}`}</span>
            <button type="button" title={t("promotion.action.refresh")} aria-label={t("promotion.action.refresh")}
              disabled={loading} onClick={() => void loadPromotions()}><ArrowClockwise size={16} /></button>
          </footer>
          {status && ![t("promotion.status.loaded"), t("promotion.list.empty")].includes(status)
            && <p className="promotion-list-message" role="status">{status}</p>}
        </section>

        <section className="promotion-detail-panel" aria-label={selectedPromotion?.name}>
          {selectedPromotion ? <>
            <section className="promotion-overview-card">
              <header className="promotion-detail-heading">
                <div className="promotion-detail-title">
                  <h2>{selectedPromotion.name}</h2>
                  <span className={`promotion-state ${isPromotionExpired(selectedPromotion, today) ? "expired" : selectedPromotion.status.toLowerCase()}`}>
                    <Circle size={10} weight="fill" aria-hidden="true" />
                    {isPromotionExpired(selectedPromotion, today) ? t("promotion.list.expired") : t(`promotion.status.${selectedPromotion.status}`)}
                  </span>
                </div>
                <div className="promotion-detail-actions" ref={actionsRef} onKeyDown={event => {
                  if (event.key === "Escape") { setActionsOpen(false); actionsButtonRef.current?.focus(); }
                }}>
                  <button type="button" disabled={pendingAction !== ""}
                    onClick={() => void runAction(selectedPromotion.status === "ACTIVE" ? "deactivate" : "activate", selectedPromotion)}>
                    {t(`promotion.action.${selectedPromotion.status === "ACTIVE" ? "deactivate" : "activate"}`)}
                  </button>
                  <button type="button" ref={actionsButtonRef} className="promotion-more-button" title={t("promotion.action.more")}
                    aria-label={t("promotion.action.more")} aria-expanded={actionsOpen} aria-controls="promotion-more-actions"
                    onClick={() => setActionsOpen(current => !current)}><DotsThree size={20} weight="bold" /></button>
                  {actionsOpen && <div className="promotion-action-menu" id="promotion-more-actions">
                    {(["duplicate", "delete"] as const).map(action => <button type="button" key={action}
                      disabled={pendingAction !== "" || promotionActionDisabled(action, selectedPromotion)}
                      onClick={() => { setActionsOpen(false); void runAction(action, selectedPromotion); }}>
                      {t(`promotion.action.${action}`)}
                    </button>)}
                  </div>}
                </div>
              </header>
              <dl className="promotion-facts">
                <div>
                  <dt><CalendarBlank size={17} aria-hidden="true" />{t("stock.column.promotionValidity")}</dt>
                  <dd>{formatPromotionDateRange(selectedPromotion, locale, t("promotion.noEndDate"))}</dd>
                </div>
                <div>
                  <dt><Tag size={17} aria-hidden="true" />{t("promotion.field.type")}</dt>
                  <dd>{promotionTypeLabel(selectedPromotion, t, locale)}</dd>
                </div>
                <div>
                  <dt><ChartBar size={17} aria-hidden="true" />{t("promotion.detail.usageCount")}</dt>
                  <dd>{number.format(selectedPromotion.usageCount ?? 0)}</dd>
                </div>
              </dl>
              <section className="promotion-conditions-section">
                <h3>{t("promotion.detail.conditions")}</h3>
                <ol className="promotion-condition-list">
                  {conditions.map((text, index) => <li key={index}><span aria-hidden="true">{index + 1}</span><p>{text}</p></li>)}
                </ol>
              </section>
            </section>
            <section className="promotion-products-section">
              <h3>{t("promotion.detail.products")} ({number.format(selectedProducts.length)})</h3>
              {productsLoading ? <p className="promotion-empty">{t("common.loading")}</p>
                : selectedProducts.length === 0 ? <p className="promotion-empty">{t("promotion.detail.noProducts")}</p>
                  : <div className="promotion-products-table-scroll"><table className="promotion-products-table">
                    <thead><tr>
                      <th>{t("promotion.detail.code")}</th>
                      <th>{t("promotion.detail.product")}</th>
                      <th>{t("promotion.detail.price")}</th>
                    </tr></thead>
                    <tbody>{selectedProducts.map(product => <tr key={product.id}>
                      <td>{product.code || product.barcode || product.id}</td>
                      <td>{product.name || product.code || product.id}</td>
                      <td className="numeric">{product.salePrice == null ? "—" : currency.format(Number(product.salePrice))}</td>
                    </tr>)}</tbody>
                  </table></div>}
            </section>
          </> : <div className="promotion-detail-empty">{t("promotion.list.selectPrompt")}</div>}
        </section>
      </section>}
      {app !== "gestion" && <ScreenContextFooter locale={locale} terminalContext={terminalContext} />}
    </main>
  );
}

type PromotionCatalogProduct = {
  id: string;
  active?: boolean | null;
  code?: string | null;
  barcode?: string | null;
  name?: string | null;
  salePrice?: number | string | null;
  familyId?: string | null;
  subfamilyId?: string | null;
};



function PromotionGroup({
  promotions, selectedId, locale, t, onSelect, expired
}: {
  promotions: PromotionView[];
  selectedId: string | null;
  locale: LocaleCode;
  t: PromotionTranslator;
  onSelect: (id: string) => void;
  expired: boolean;
}) {
  if (promotions.length === 0) return null;
  return <div className={`promotion-list-group${expired ? " expired" : ""}`}>
    {promotions.map(promotion => (
      <button type="button" key={promotion.id} aria-label={promotion.name} aria-pressed={selectedId === promotion.id}
        className={`promotion-list-row${selectedId === promotion.id ? " selected" : ""}${expired ? " expired" : ""} ${promotion.status.toLowerCase()}`}
        onClick={() => onSelect(promotion.id)}
      >
        <Circle className="promotion-row-indicator" size={10} weight="fill" aria-hidden="true" />
        <span className="promotion-list-row-copy">
          <strong>{promotion.name}</strong>
          <small>{formatPromotionDateRange(promotion, locale, t("promotion.noEndDate"))}</small>
        </span>
        <span className="promotion-row-status">{expired ? t("promotion.list.expired") : t(`promotion.status.${promotion.status}`)}</span>
      </button>
    ))}
  </div>;
}

function isPromotionExpired(promotion: PromotionView, today: string) {
  return Boolean(promotion.endDate && promotion.endDate < today);
}

function localIsoDate(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function promotionMatchesProduct(promotion: PromotionView, product: PromotionCatalogProduct) {
  if ((promotion.scope ?? "SALE") === "SALE") return true;
  return (promotion.targets ?? []).some(target => {
    if (target.type === "PRODUCT") return target.targetId === product.id;
    if (target.type === "FAMILY") return target.targetId === product.familyId;
    return target.type === "SUBFAMILY" && target.targetId === product.subfamilyId;
  });
}
