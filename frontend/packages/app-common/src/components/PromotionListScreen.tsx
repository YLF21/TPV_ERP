import { useEffect, useMemo, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, TerminalContext, UserSession } from "../types";
import { ScreenContextFooter } from "./ScreenContextFooter";
import { SessionTopControls } from "./SessionTopControls";
import { PromotionWizard } from "./PromotionWizard";
import type { PromotionView } from "./PromotionWizard";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { tableLayoutGridTemplate, visibleTableColumns } from "./tableLayoutPreferences";
import type { TableColumnDefinition } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

type PromotionListScreenProps = {
  app: AppKind;
  locale: LocaleCode;
  session: UserSession;
  terminalContext: TerminalContext;
  onBack: () => void;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
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
  onLogout
}: PromotionListScreenProps) {
  const t = createTranslator(locale);
  const [promotions, setPromotions] = useState<PromotionView[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [status, setStatus] = useState("");
  const [pendingAction, setPendingAction] = useState("");
  const token = session.accessToken;
  const tableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: token,
    tableKey: promotionListTableKey,
    definitions: promotionListColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const gridStyle = {
    gridTemplateColumns: `${tableLayoutGridTemplate(tableLayout.layout)} minmax(330px, auto)`
  };

  function columnLabel(column: PromotionListColumnKey): string {
    return t(`promotion.column.${column}`);
  }

  function renderCell(column: PromotionListColumnKey, promotion: PromotionView) {
    if (column === "name") {
      return <button type="button" data-column-key={column} key={column} onClick={() => setSelectedId(promotion.id)}>{promotion.name}</button>;
    }
    if (column === "status") return <span data-column-key={column} key={column}>{t(`promotion.status.${promotion.status}`)}</span>;
    if (column === "type") return <span data-column-key={column} key={column}>{t(`promotion.type.${promotion.type}`)}</span>;
    if (column === "date") return <span data-column-key={column} key={column}>{promotionDateRange(promotion)}</span>;
    return <span data-column-key={column} key={column}>{t(`promotion.segment.${promotion.customerSegment ?? "ALL"}`)}</span>;
  }

  const selectedPromotion = useMemo(
    () => promotions.find((promotion) => promotion.id === selectedId) ?? null,
    [promotions, selectedId]
  );

  async function loadPromotions() {
    try {
      setLoading(true);
      const rows = await apiRequest<PromotionView[]>("/promotions", { token });
      setPromotions(rows);
      setStatus(rows.length === 0 ? t("promotion.list.empty") : t("promotion.status.loaded"));
      setSelectedId((current) => rows.some((promotion) => promotion.id === current)
        ? current
        : rows[0]?.id ?? null);
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
    <main className="promotion-screen work-screen">
      <SessionTopControls
        locale={locale}
        session={session}
        languageLabel={t("login.language")}
        shutdownLabel={t("login.shutdown")}
        changePasswordLabel={t("common.changePassword")}
        logoutLabel={t("common.logout")}
        shutdownConfirmTitle={t("login.shutdownConfirmTitle")}
        shutdownConfirmText={t("login.shutdownConfirmText")}
        noLabel={t("common.no")}
        yesLabel={t("common.yes")}
        onLocaleChange={onLocaleChange}
        onLogout={onLogout}
      />

      <section className="work-shell promotion-shell" aria-label={t("promotion.list.title")}>
        <header className="work-topbar">
          <button type="button" className="report-brand-back" onClick={onBack}>
            {t(app === "venta" ? "venta.title" : "gestion.title")}
          </button>
          <h1 className="report-title">{t("promotion.list.title")}</h1>
        </header>

        <section className="work-panel promotion-list-panel">
          <header className="work-panel-heading stock-panel-heading">
            <div>
              <h2>{t("promotion.list.heading")}</h2>
              <span>{loading ? t("promotion.status.loading") : status}</span>
            </div>
            <button type="button" className="stock-filter-button" onClick={() => void loadPromotions()}>
              {t("promotion.action.refresh")}
            </button>
          </header>

          <div className="promotion-table">
            <div className="promotion-row promotion-row-head" style={gridStyle}>
              {visibleColumns.map((column) => (
                <TableLayoutHeaderCell
                  as="span"
                  column={column}
                  key={column.key}
                  resizeLabel={`${t("stock.columns.resize")} ${columnLabel(column.key)}`}
                  onReorder={tableLayout.reorderColumns}
                  onMove={tableLayout.moveColumn}
                  onResize={tableLayout.resizeColumn}
                >
                  {columnLabel(column.key)}
                </TableLayoutHeaderCell>
              ))}
              <span data-fixed-column="actions">{t("promotion.column.actions")}</span>
            </div>
            {promotions.map((promotion) => (
              <div className={`promotion-row ${selectedId === promotion.id ? "selected" : ""}`} style={gridStyle} key={promotion.id}>
                {visibleColumns.map((column) => renderCell(column.key, promotion))}
                <span className="promotion-row-actions" data-fixed-column="actions">
                  {(["duplicate", "activate", "deactivate", "delete"] as const).map((action) => (
                    <button
                      type="button"
                      key={action}
                      disabled={pendingAction !== "" || promotionActionDisabled(action, promotion)}
                      onClick={() => void runAction(action, promotion)}
                    >
                      {t(`promotion.action.${action}`)}
                    </button>
                  ))}
                </span>
              </div>
            ))}
            {!loading && promotions.length === 0 && (
              <p className="promotion-empty">{t("promotion.list.empty")}</p>
            )}
          </div>
        </section>

        <section className="work-panel promotion-detail-panel">
          <PromotionWizard
            locale={locale}
            session={session}
            onCreated={(promotion) => {
              setPromotions((current) => [promotion, ...current]);
              setSelectedId(promotion.id);
              setStatus(t("promotion.status.created"));
            }}
          />
          {selectedPromotion && (
            <aside className="promotion-selected">
              <strong>{t("promotion.list.selected")}</strong>
              <span>{selectedPromotion.name}</span>
              <span>{t(`promotion.status.${selectedPromotion.status}`)}</span>
              <span>
                {t(`promotion.scope.${selectedPromotion.scope ?? "SALE"}`)}
                {selectedPromotion.targets?.length ? ` (${selectedPromotion.targets.length})` : ""}
              </span>
            </aside>
          )}
        </section>

        <ScreenContextFooter locale={locale} terminalContext={terminalContext} />
      </section>
    </main>
  );
}

export function promotionDateRange(promotion: PromotionView) {
  return promotion.endDate ? `${promotion.startDate} - ${promotion.endDate}` : promotion.startDate;
}
