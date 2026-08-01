import {
  TableLayoutHeaderCell,
  tableLayoutGridTemplate,
  useTableLayoutPreference,
  visibleTableColumns,
  type TableColumnDefinition,
  type UserSession
} from "@tpverp/app-common";
import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import {
  loadCashCurrentBalances,
  type CashCurrentBalance,
  type CashCurrentBalances,
  type CashCurrentBalanceStatus
} from "./cashCurrentBalancesApi";

type Translator = (key: string) => string;
type Props = { session: UserSession; t: Translator };
type ColumnKey = "terminal" | "status" | "user" | "openedAt" | "expectedCash" | "lastActivity";

const REFRESH_INTERVAL_MS = 3_000;

export const cashCurrentBalancesTableKey = "gestion.cashCurrentBalances.current";
export const cashCurrentBalanceColumnDefinitions = [
  { key: "terminal", defaultWidth: 220 },
  { key: "status", defaultWidth: 135 },
  { key: "user", defaultWidth: 220 },
  { key: "openedAt", defaultWidth: 175 },
  { key: "expectedCash", defaultWidth: 205 },
  { key: "lastActivity", defaultWidth: 180 }
] as const satisfies readonly TableColumnDefinition<ColumnKey>[];

export function canReadCashCurrentBalances(session: UserSession) {
  return session.permissions.some((permission) => (
    permission === "ADMIN" || permission === "GESTION_CUENTAS" || permission === "CASH_READ"
  ));
}

export function CashCurrentBalancesScreen({ session, t }: Props) {
  const token = session.accessToken;
  const [snapshot, setSnapshot] = useState<CashCurrentBalances | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [reloadGeneration, setReloadGeneration] = useState(0);
  const tableLayout = useTableLayoutPreference({
    app: "gestion",
    username: session.username,
    accessToken: token,
    tableKey: cashCurrentBalancesTableKey,
    definitions: cashCurrentBalanceColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const tableStyle = { gridTemplateColumns: tableLayoutGridTemplate(tableLayout.layout) } as CSSProperties;
  const moneyFormatter = useMemo(() => new Intl.NumberFormat("es-ES", {
    style: "currency",
    currency: "EUR"
  }), []);
  const dateTimeFormatter = useMemo(() => new Intl.DateTimeFormat("es-ES", {
    dateStyle: "short",
    timeStyle: "medium",
    timeZone: snapshot?.timezone
  }), [snapshot?.timezone]);
  const timeFormatter = useMemo(() => new Intl.DateTimeFormat("es-ES", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZone: snapshot?.timezone
  }), [snapshot?.timezone]);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let inFlight = false;
    let firstLoad = true;

    const schedule = () => {
      if (!active) return;
      timer = setTimeout(() => void refresh(), REFRESH_INTERVAL_MS);
    };
    const refresh = async () => {
      if (!active || inFlight) return;
      if (!firstLoad && document.visibilityState === "hidden") {
        schedule();
        return;
      }
      inFlight = true;
      try {
        const next = await loadCashCurrentBalances(token);
        if (!active) return;
        setSnapshot(next);
        setLoadError(false);
      } catch {
        if (active) setLoadError(true);
      } finally {
        firstLoad = false;
        inFlight = false;
        if (active) {
          setLoading(false);
          schedule();
        }
      }
    };
    const refreshWhenVisible = () => {
      if (document.visibilityState !== "visible") return;
      if (timer) clearTimeout(timer);
      void refresh();
    };
    void refresh();
    document.addEventListener("visibilitychange", refreshWhenVisible);
    window.addEventListener("focus", refreshWhenVisible);
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
      window.removeEventListener("focus", refreshWhenVisible);
    };
  }, [reloadGeneration, token]);

  const rows = snapshot?.terminals ?? [];
  const totalCash = rows.reduce((total, terminal) => total + Number(terminal.expectedCash || 0), 0);
  const openCount = rows.filter((terminal) => terminal.status === "ABIERTA").length;

  function renderCell(row: CashCurrentBalance, column: ColumnKey) {
    if (column === "terminal") return <strong>{row.terminalName}</strong>;
    if (column === "status") {
      return <span className={`gestion-cash-balance-status ${row.status.toLocaleLowerCase()}`}>{statusLabel(row.status, t)}</span>;
    }
    if (column === "user") {
      if (!row.openingUserName) return null;
      const showLogin = row.openingUsername
        && row.openingUsername.toLocaleLowerCase() !== row.openingUserName.toLocaleLowerCase();
      return <span className="gestion-cash-closure-user"><strong>{row.openingUserName}</strong>{showLogin && <small>{row.openingUsername}</small>}</span>;
    }
    if (column === "openedAt") return row.openedAt ? dateTimeFormatter.format(new Date(row.openedAt)) : null;
    if (column === "expectedCash") return <strong>{moneyFormatter.format(row.expectedCash)}</strong>;
    return row.lastActivityAt ? dateTimeFormatter.format(new Date(row.lastActivityAt)) : null;
  }

  return (
    <section className="gestion-workspace gestion-cash-balances-workspace">
      <header className="gestion-dashboard-toolbar gestion-cash-balances-header">
        <div>
          <span className="gestion-eyebrow">{t("gestion.cashCurrentBalances.eyebrow")}</span>
          <h2>{t("gestion.cashCurrentBalances.title")}</h2>
          <p>{t("gestion.cashCurrentBalances.subtitle")}</p>
        </div>
        <button type="button" disabled={loading} onClick={() => setReloadGeneration((current) => current + 1)}>
          {t("gestion.cashCurrentBalances.refresh")}
        </button>
      </header>

      <div className="gestion-cash-balances-summary" aria-label={t("gestion.cashCurrentBalances.summary")}>
        <div className="total">
          <span>{t("gestion.cashCurrentBalances.totalExpected")}</span>
          <strong>{moneyFormatter.format(totalCash)}</strong>
        </div>
        <div>
          <span>{t("gestion.cashCurrentBalances.openTerminals")}</span>
          <strong>{openCount}</strong>
        </div>
        <div>
          <span>{t("gestion.cashCurrentBalances.activeTerminals")}</span>
          <strong>{rows.length}</strong>
        </div>
        <div className="updated">
          <span>{t("gestion.cashCurrentBalances.updatedAt")}</span>
          <strong>{snapshot?.asOf ? timeFormatter.format(new Date(snapshot.asOf)) : null}</strong>
        </div>
      </div>

      {loadError && snapshot && (
        <div className="gestion-cash-balances-warning" role="alert">
          {t("gestion.cashCurrentBalances.staleWarning")}
        </div>
      )}

      <section className="gestion-cash-balances-list" aria-label={t("gestion.cashCurrentBalances.title")}>
        <div className="gestion-cash-balances-table" role="table">
          <div className="gestion-cash-balance-row head" role="row" style={tableStyle}>
            {visibleColumns.map((column) => (
              <TableLayoutHeaderCell
                as="span"
                key={column.key}
                column={column}
                resizeLabel={`${t("gestion.cashCurrentBalances.resize")} ${t(`gestion.cashCurrentBalances.column.${column.key}`)}`}
                onReorder={tableLayout.reorderColumns}
                onMove={tableLayout.moveColumn}
                onResize={tableLayout.resizeColumn}
              >
                {t(`gestion.cashCurrentBalances.column.${column.key}`)}
              </TableLayoutHeaderCell>
            ))}
          </div>
          <div className="gestion-cash-balances-body">
            {rows.map((row) => (
              <div className="gestion-cash-balance-row" role="row" style={tableStyle} key={row.terminalId}>
                {visibleColumns.map((column) => (
                  <span role="cell" data-column-key={column.key} key={column.key} title={plainCellTitle(row, column.key, t, moneyFormatter, dateTimeFormatter)}>
                    {renderCell(row, column.key)}
                  </span>
                ))}
              </div>
            ))}
            {loading && <div className="gestion-cash-balances-state">{t("gestion.cashCurrentBalances.loading")}</div>}
            {!loading && loadError && !snapshot && (
              <div className="gestion-cash-balances-state error" role="alert">
                <span>{t("gestion.cashCurrentBalances.loadError")}</span>
                <button type="button" onClick={() => setReloadGeneration((current) => current + 1)}>{t("gestion.cashCurrentBalances.retry")}</button>
              </div>
            )}
            {!loading && !loadError && rows.length === 0 && (
              <div className="gestion-cash-balances-state">{t("gestion.cashCurrentBalances.empty")}</div>
            )}
          </div>
        </div>
        <footer>
          <span>{t("gestion.cashCurrentBalances.terminalCount").replace("{count}", String(rows.length))}</span>
          <span>{t("gestion.cashCurrentBalances.definition")}</span>
        </footer>
      </section>
    </section>
  );
}

function statusLabel(status: CashCurrentBalanceStatus, t: Translator) {
  if (status === "ABIERTA") return t("gestion.cashCurrentBalances.status.open");
  if (status === "CERRADA") return t("gestion.cashCurrentBalances.status.closed");
  return t("gestion.cashCurrentBalances.status.neverOpened");
}

function plainCellTitle(
  row: CashCurrentBalance,
  column: ColumnKey,
  t: Translator,
  money: Intl.NumberFormat,
  dateTime: Intl.DateTimeFormat
) {
  if (column === "terminal") return row.terminalName;
  if (column === "status") return statusLabel(row.status, t);
  if (column === "user") return row.openingUserName ?? "";
  if (column === "openedAt") return row.openedAt ? dateTime.format(new Date(row.openedAt)) : "";
  if (column === "expectedCash") return money.format(row.expectedCash);
  return row.lastActivityAt ? dateTime.format(new Date(row.lastActivityAt)) : "";
}
