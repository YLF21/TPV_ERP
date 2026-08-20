import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, UserSession } from "../types";
import { ErpSelect } from "./ErpSelect";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type {
  TableColumnDefinition,
  TableColumnMoveDirection,
  TableLayout
} from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";
import { sortTableRows, useTableSortPreference, type TableSortValue } from "./tableSorting";
import "./MemberLoyaltyPanel.css";

export type MemberLoyaltyRequest = typeof apiRequest;

export type MemberView = {
  id: string; customerId: string; memberId: string; numMember?: string | null;
  balance: number | string; returnCreditBalance?: number | string; points: number; categoryId?: string | null;
  autoCategoryLocked: boolean; active: boolean;
};

export type MemberMovement = {
  id: string; type: string; balanceAmount: number | string; pointsAmount: number;
  documentId?: string | null; previousCategoryId?: string | null; newCategoryId?: string | null;
  reason?: string | null; createdAt: string;
};

export type MemberCategory = {
  id: string; code: string; name: string; minPoints: number;
  discountPercent: number | string; discountEnabled: boolean; manualOnly: boolean;
  active: boolean; sortOrder: number;
};

export type MemberCardDelivery = {
  id: string; memberId: string; email: string; status: string; createdAt: string;
  sentAt?: string | null; errorMessage?: string | null;
};

type Translate = (key: string) => string;
type Tab = "detail" | "movements" | "deliveries";
export type MemberMovementColumnKey = "date" | "movement" | "amount" | "reason";
export type MemberCardDeliveryColumnKey = "email" | "status" | "date";

export const memberLoyaltyTableKeys = {
  movements: "party.members.movements",
  deliveries: "party.memberCardDeliveries"
} as const;

export const memberMovementColumnDefinitions = [
  { key: "date", defaultWidth: 180 },
  { key: "movement", defaultWidth: 160 },
  { key: "amount", defaultWidth: 120 },
  { key: "reason", defaultWidth: 280 }
] as const satisfies readonly TableColumnDefinition<MemberMovementColumnKey>[];

export const memberCardDeliveryColumnDefinitions = [
  { key: "email", defaultWidth: 280 },
  { key: "status", defaultWidth: 140 },
  { key: "date", defaultWidth: 180 }
] as const satisfies readonly TableColumnDefinition<MemberCardDeliveryColumnKey>[];

export type MemberLoyaltyPanelProps = {
  app?: AppKind;
  memberId: string;
  session: UserSession;
  t?: Translate;
  request?: MemberLoyaltyRequest;
};

type TableLayoutController<Key extends string> = {
  layout: TableLayout<Key>;
  reorderColumns: (draggedKey: Key, targetKey: Key) => void;
  moveColumn: (columnKey: Key, direction: TableColumnMoveDirection) => void;
  resizeColumn: (columnKey: Key, width: number) => void;
};

type OperationalColumn<Key extends string, Row> = {
  key: Key;
  label: string;
  render: (row: Row) => ReactNode;
  sortValue?: (row: Row) => TableSortValue;
};

function MemberOperationalTable<Key extends string, Row extends { id: string }>({
  columns,
  layoutController,
  app,
  username,
  tableKey,
  rows,
  resizeLabel,
  actionLabel,
  actionWidth = 0,
  renderActions
}: {
  columns: readonly OperationalColumn<Key, Row>[];
  layoutController: TableLayoutController<Key>;
  app: AppKind;
  username: string;
  tableKey: string;
  rows: readonly Row[];
  resizeLabel: string;
  actionLabel?: string;
  actionWidth?: number;
  renderActions?: (row: Row) => ReactNode;
}) {
  const tableSort = useTableSortPreference({
    app,
    username,
    tableKey,
    columns: columns.map((column) => column.key),
    defaultSort: null
  });
  const visibleColumns = visibleTableColumns(layoutController.layout);
  const columnsByKey = new Map(columns.map((column) => [column.key, column]));
  const tableWidth = visibleColumns.reduce((total, column) => total + column.width, actionWidth);
  const sortedRows = sortTableRows(rows, tableSort.sort, (row, column) => {
    const definition = columnsByKey.get(column);
    const value = definition?.sortValue?.(row) ?? definition?.render(row);
    return typeof value === "string" || typeof value === "number" || typeof value === "boolean" || value instanceof Date
      ? value
      : null;
  });

  return <div style={{ overflowX: "auto" }}>
    <table style={{ tableLayout: "fixed", minWidth: tableWidth }}>
      <colgroup>
        {visibleColumns.map((column) => <col key={column.key} style={{ width: column.width }} />)}
        {renderActions && <col style={{ width: actionWidth }} />}
      </colgroup>
      <thead><tr>
        {visibleColumns.map((column) => {
          const definition = columnsByKey.get(column.key);
          if (!definition) return null;
          return <TableLayoutHeaderCell
            column={column}
            key={column.key}
            sortDirection={tableSort.sort?.column === column.key ? tableSort.sort.direction : null}
            sortLabel={definition.label}
            onSort={tableSort.toggleSort}
            resizeLabel={`${resizeLabel} ${definition.label}`}
            onReorder={layoutController.reorderColumns}
            onMove={layoutController.moveColumn}
            onResize={layoutController.resizeColumn}
          >
            {definition.label}
          </TableLayoutHeaderCell>;
        })}
        {renderActions && <th data-fixed-column="actions">{actionLabel}</th>}
      </tr></thead>
      <tbody>{sortedRows.map((row) => <tr key={row.id}>
        {visibleColumns.map((column) => {
          const definition = columnsByKey.get(column.key);
          return <td data-column-key={column.key} key={column.key}>{definition?.render(row)}</td>;
        })}
        {renderActions && <td data-fixed-column="actions">{renderActions(row)}</td>}
      </tr>)}</tbody>
    </table>
  </div>;
}

export function memberLoyaltyPermissions(session: UserSession) {
  const admin = session.permissions.includes("ADMIN");
  const partyManager = session.permissions.includes("GESTION_CLIENTE_PROVEEDOR");
  return {
    canWrite: admin || partyManager || session.permissions.includes("CUSTOMERS_WRITE"),
    canSetCategory: admin || partyManager || session.permissions.includes("CUSTOMERS_WRITE")
  };
}

export function memberLoyaltyAdjustmentBody(value: string, reason: string, kind: "balance" | "points") {
  const cleanReason = reason.trim();
  if (!cleanReason) throw new Error("party.members.reasonRequired");
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount === 0 || (kind === "points" && !Number.isInteger(amount))) {
    throw new Error("party.members.adjustmentInvalid");
  }
  return kind === "balance" ? { amount, reason: cleanReason } : { points: amount, reason: cleanReason };
}

export async function loadMemberLoyalty(memberId: string, token: string, request: MemberLoyaltyRequest = apiRequest) {
  const options = { token };
  const [member, movements, categories] = await Promise.all([
    request<MemberView>(`/members/${memberId}`, options),
    request<MemberMovement[]>(`/members/${memberId}/movements`, options),
    request<MemberCategory[]>("/member-categories", options)
  ]);
  return { member, movements, categories };
}

const fallback: Translate = (key) => key;

export type MemberMovementTone = "credit" | "debit" | "manual" | "status";

const manualMovementTypes = new Set(["AJUSTE_MANUAL_SALDO", "AJUSTE_MANUAL_PUNTOS", "AJUSTE_SAAS"]);

export function memberMovementPresentation(
  movement: MemberMovement,
  categories: readonly MemberCategory[],
  t: Translate,
  locale = "es-ES"
) {
  const points = Number(movement.pointsAmount || 0);
  const balance = Number(movement.balanceAmount || 0);
  const amount = points !== 0 ? points : balance;
  const tone: MemberMovementTone = manualMovementTypes.has(movement.type)
    ? "manual"
    : amount > 0 ? "credit" : amount < 0 ? "debit" : "status";
  let formattedAmount = "—";
  if (points !== 0) {
    formattedAmount = `${points > 0 ? "+" : "-"}${new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(Math.abs(points))} pt`;
  } else if (balance !== 0) {
    formattedAmount = `${balance > 0 ? "+" : "-"}${new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Math.abs(balance))} €`;
  } else if (movement.type === "CAMBIO_CATEGORIA") {
    const categoryName = (id?: string | null) => categories.find((category) => category.id === id)?.name || "—";
    formattedAmount = `${categoryName(movement.previousCategoryId)} → ${categoryName(movement.newCategoryId)}`;
  }
  return {
    tone,
    label: t(`party.members.movementType.${movement.type}`),
    amount: formattedAmount
  };
}

export function MemberLoyaltyPanel({ app = "venta", memberId, session, t = fallback, request = apiRequest }: MemberLoyaltyPanelProps) {
  const [tab, setTab] = useState<Tab>("detail");
  const [visitedTabs, setVisitedTabs] = useState<Set<Tab>>(() => new Set(["detail"]));
  const [member, setMember] = useState<MemberView | null>(null);
  const [movements, setMovements] = useState<MemberMovement[]>([]);
  const [categories, setCategories] = useState<MemberCategory[]>([]);
  const [deliveries, setDeliveries] = useState<MemberCardDelivery[]>([]);
  const [adjustment, setAdjustment] = useState({ kind: "points" as "points" | "balance", value: "", reason: "" });
  const [categoryId, setCategoryId] = useState("");
  const [categoryReason, setCategoryReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const permissions = memberLoyaltyPermissions(session);
  const options = useMemo(() => ({ token: session.accessToken }), [session.accessToken]);
  const movementTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: visitedTabs.has("movements") ? session.accessToken : undefined,
    tableKey: memberLoyaltyTableKeys.movements,
    definitions: memberMovementColumnDefinitions
  });
  const deliveryTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: visitedTabs.has("deliveries") ? session.accessToken : undefined,
    tableKey: memberLoyaltyTableKeys.deliveries,
    definitions: memberCardDeliveryColumnDefinitions
  });

  function selectTab(nextTab: Tab) {
    setError("");
    setTab(nextTab);
    setVisitedTabs((current) => current.has(nextTab) ? current : new Set([...current, nextTab]));
  }

  const reload = useCallback(async () => {
    setBusy(true); setError("");
    try {
      const data = await loadMemberLoyalty(memberId, session.accessToken ?? "", request);
      setMember(data.member); setMovements(data.movements); setCategories(data.categories);
      setCategoryId(data.member.categoryId ?? "");
    } catch (cause) { setError(cause instanceof Error ? cause.message : t("party.loadError")); }
    finally { setBusy(false); }
  }, [memberId, request, session.accessToken, t]);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    if (tab === "deliveries" && deliveries.length === 0) void request<MemberCardDelivery[]>(`/member-card-deliveries?memberId=${encodeURIComponent(memberId)}`, options).then(setDeliveries).catch(showError);
  }, [tab]);

  function showError(cause: unknown) { setError(cause instanceof Error ? cause.message : t("party.loadError")); }
  async function mutate(action: () => Promise<unknown>, refresh = true) {
    setBusy(true); setError("");
    try { await action(); if (refresh) await reload(); }
    catch (cause) { showError(cause); }
    finally { setBusy(false); }
  }

  function submitAdjustment(event: React.FormEvent) {
    event.preventDefault();
    let body: object;
    try { body = memberLoyaltyAdjustmentBody(adjustment.value, adjustment.reason, adjustment.kind); }
    catch (cause) { showError(cause); return; }
    void mutate(() => request(`/members/${memberId}/${adjustment.kind}-adjustments`, { ...options, method: "POST", body }))
      .then(() => setAdjustment((value) => ({ ...value, value: "", reason: "" })));
  }

  const movementColumns: readonly OperationalColumn<MemberMovementColumnKey, MemberMovement>[] = [
    { key: "date", label: t("party.members.date"), render: (item) => new Date(item.createdAt).toLocaleString(), sortValue: (item) => new Date(item.createdAt) },
    { key: "movement", label: t("party.members.movement"), render: (item) => {
      const presentation = memberMovementPresentation(item, categories, t);
      return <span className={`member-movement-type member-movement-type--${presentation.tone}`}>{presentation.label}</span>;
    } },
    { key: "amount", label: t("party.members.amount"), render: (item) => {
      const presentation = memberMovementPresentation(item, categories, t);
      return <strong className={`member-movement-amount member-movement-amount--${presentation.tone}`}>{presentation.amount}</strong>;
    }, sortValue: (item) => item.pointsAmount || Number(item.balanceAmount) },
    { key: "reason", label: t("party.members.reason"), render: (item) => item.reason || "—" }
  ];
  const deliveryColumns: readonly OperationalColumn<MemberCardDeliveryColumnKey, MemberCardDelivery>[] = [
    { key: "email", label: t("party.email"), render: (item) => item.email },
    { key: "status", label: t("party.status"), render: (item) => t(`party.members.deliveryStatus.${item.status}`) },
    { key: "date", label: t("party.members.date"), render: (item) => new Date(item.createdAt).toLocaleString(), sortValue: (item) => new Date(item.createdAt) }
  ];
  const tabs: Tab[] = ["detail", "movements", "deliveries"];
  return <section className="stock-section party-member-loyalty" aria-label={t("party.members.loyaltyTitle")}>
    <div className="stock-section__header">
      <h3>{t("party.members.loyaltyTitle")}</h3>
      <div className="stock-toolbar member-loyalty-tabs" role="tablist">{tabs.map((item) =>
        <button key={item} type="button" role="tab" aria-selected={tab === item} className={tab === item ? "is-active" : ""} onClick={() => selectTab(item)}>{t(`party.members.tab.${item}`)}</button>
      )}</div>
    </div>
    {error && <p role="alert" className="form-error">{t(error)}</p>}
    {busy && !member ? <p>{t("common.loading")}</p> : null}

    {tab === "detail" && member && <>
      <div className="stock-summary-grid">
        <Summary label={t("party.memberId")} value={member.memberId} />
        <Summary label={t("party.numMember")} value={member.numMember || "—"} />
        <Summary label={t("party.members.points")} value={String(member.points)} />
        <Summary label={t("party.members.balance")} value={String(member.balance)} />
        <Summary label={t("party.members.returnCreditBalance")} value={String(member.returnCreditBalance ?? 0)} />
        <Summary label={t("party.status")} value={t(member.active ? "party.active" : "party.inactive")} />
      </div>
      <div className="member-loyalty-editor-grid">
        {permissions.canSetCategory && <section className="member-loyalty-card">
          <h4>{t("party.members.category")}</h4>
          <form onSubmit={(event) => {
            event.preventDefault();
            if (!categoryReason.trim()) { setError("party.members.reasonRequired"); return; }
            void mutate(() => request(`/members/${memberId}/category`, { ...options, method: "PUT", body: { categoryId: categoryId || null, lockAutomatic: member.autoCategoryLocked, reason: categoryReason.trim() } })).then(() => setCategoryReason(""));
          }}>
            <label><span>{t("party.members.category")}</span><ErpSelect value={categoryId} onChange={setCategoryId} options={[{ value: "", label: "—" }, ...categories.filter((item) => item.active || item.id === categoryId).map((item) => ({ value: item.id, label: item.name }))]} /></label>
            <label><span>{t("party.members.reason")}</span><input value={categoryReason} onChange={(event) => setCategoryReason(event.target.value)} /></label>
            <label className="member-loyalty-check"><input type="checkbox" checked={member.autoCategoryLocked} onChange={(event) => setMember({ ...member, autoCategoryLocked: event.target.checked })} /><span>{t("party.members.autoCategoryLocked")}</span></label>
            <button disabled={busy}>{t("common.save")}</button>
          </form>
        </section>}
        {permissions.canWrite && <section className="member-loyalty-card">
          <h4>{t("party.members.adjustmentsTitle")}</h4>
          <form onSubmit={submitAdjustment}>
            <label><span>{t("party.members.adjustmentType")}</span><ErpSelect value={adjustment.kind} onChange={(value) => setAdjustment((current) => ({ ...current, kind: value as "points" | "balance" }))} options={[{ value: "points", label: t("party.members.points") }, { value: "balance", label: t("party.members.balance") }]} /></label>
            <label><span>{t("party.members.amount")}</span><input type="number" step={adjustment.kind === "balance" ? "0.01" : "1"} value={adjustment.value} onChange={(event) => setAdjustment((value) => ({ ...value, value: event.target.value }))} /></label>
            <label><span>{t("party.members.reason")}</span><input value={adjustment.reason} onChange={(event) => setAdjustment((value) => ({ ...value, reason: event.target.value }))} /></label>
            <button disabled={busy}>{t("party.members.adjust")}</button>
          </form>
        </section>}
      </div>
    </>}

    {tab === "movements" && <section className="member-loyalty-list-section">
      <header><h4>{t("party.members.movementsTitle")}</h4><span>{movements.length}</span></header>
      <MemberOperationalTable
        app={app}
        username={session.username}
        tableKey={memberLoyaltyTableKeys.movements}
        columns={movementColumns}
        layoutController={movementTableLayout}
        rows={movements}
        resizeLabel={t("stock.columns.resize")}
      />
      {movements.length === 0 && <p className="member-loyalty-empty">{t("party.members.noMovements")}</p>}
    </section>}

    {tab === "deliveries" && <section className="member-loyalty-list-section">
      <header><div><h4>{t("party.members.cardDeliveriesTitle")}</h4><p>{t("party.members.cardDeliveriesHint")}</p></div><span>{deliveries.length}</span></header>
      <MemberOperationalTable
      app={app}
      username={session.username}
      tableKey={memberLoyaltyTableKeys.deliveries}
      columns={deliveryColumns}
      layoutController={deliveryTableLayout}
      rows={deliveries}
      resizeLabel={t("stock.columns.resize")}
      actionLabel={t("common.actions")}
      actionWidth={120}
      renderActions={(item) => permissions.canWrite && ["ERROR", "PENDIENTE"].includes(item.status) && <button disabled={busy} onClick={() => void mutate(async () => {
        const updated = await request<MemberCardDelivery>(`/member-card-deliveries/${item.id}/retry`, { ...options, method: "PATCH" });
        setDeliveries((rows) => rows.map((row) => row.id === updated.id ? updated : row));
      }, false)}>{t("party.members.retry")}</button>}
      />
      {deliveries.length === 0 && <p className="member-loyalty-empty">{t("party.members.noCardDeliveries")}</p>}
    </section>}
  </section>;
}

function Summary({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><strong>{value}</strong></div>; }
