import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { apiRequest } from "../api/client";
import type { AppKind, UserSession } from "../types";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import type {
  TableColumnDefinition,
  TableColumnMoveDirection,
  TableLayout
} from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

export type MemberLoyaltyRequest = typeof apiRequest;

export type MemberView = {
  id: string; customerId: string; memberId: string; numMember?: string | null;
  balance: number | string; points: number; categoryId?: string | null;
  autoCategoryLocked: boolean; active: boolean;
};

export type MemberMovement = {
  id: string; type: string; balanceAmount: number | string; pointsAmount: number;
  reason?: string | null; createdAt: string;
};

export type MemberCategory = {
  id: string; code: string; name: string; minPoints: number;
  discountPercent: number | string; discountEnabled: boolean; manualOnly: boolean;
  active: boolean; sortOrder: number;
};

export type MemberSettings = {
  balanceAccrualPercent: number | string; balanceExpirationPolicy: string;
  pointsPerEuro: number | string; categoryAutoEnabled: boolean;
  memberWelcomeEnabled: boolean; memberCardCodeFormat: "QR" | "BARCODE";
  welcomeSubjectTemplate?: string | null; welcomeBodyTemplate?: string | null;
};

export type CommercialChannel = { id: string; code: string; name: string; active: boolean };
export type MemberCardDelivery = {
  id: string; memberId: string; email: string; status: string; createdAt: string;
  sentAt?: string | null; errorMessage?: string | null;
};

type Translate = (key: string) => string;
type Tab = "detail" | "categories" | "settings" | "channels" | "deliveries";
export type MemberMovementColumnKey = "date" | "movement" | "amount" | "reason";
export type MemberCategoryColumnKey = "code" | "name" | "minPoints" | "discount" | "status";
export type CommercialChannelColumnKey = "code" | "name" | "status";
export type MemberCardDeliveryColumnKey = "email" | "status" | "date";

export const memberLoyaltyTableKeys = {
  movements: "party.members.movements",
  categories: "party.memberCategories",
  channels: "party.commercialChannels",
  deliveries: "party.memberCardDeliveries"
} as const;

export const memberMovementColumnDefinitions = [
  { key: "date", defaultWidth: 180 },
  { key: "movement", defaultWidth: 160 },
  { key: "amount", defaultWidth: 120 },
  { key: "reason", defaultWidth: 280 }
] as const satisfies readonly TableColumnDefinition<MemberMovementColumnKey>[];

export const memberCategoryColumnDefinitions = [
  { key: "code", defaultWidth: 120 },
  { key: "name", defaultWidth: 220 },
  { key: "minPoints", defaultWidth: 120 },
  { key: "discount", defaultWidth: 120 },
  { key: "status", defaultWidth: 110 }
] as const satisfies readonly TableColumnDefinition<MemberCategoryColumnKey>[];

export const commercialChannelColumnDefinitions = [
  { key: "code", defaultWidth: 140 },
  { key: "name", defaultWidth: 260 },
  { key: "status", defaultWidth: 110 }
] as const satisfies readonly TableColumnDefinition<CommercialChannelColumnKey>[];

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
};

function MemberOperationalTable<Key extends string, Row extends { id: string }>({
  columns,
  layoutController,
  rows,
  resizeLabel,
  actionLabel,
  actionWidth = 0,
  renderActions
}: {
  columns: readonly OperationalColumn<Key, Row>[];
  layoutController: TableLayoutController<Key>;
  rows: readonly Row[];
  resizeLabel: string;
  actionLabel?: string;
  actionWidth?: number;
  renderActions?: (row: Row) => ReactNode;
}) {
  const visibleColumns = visibleTableColumns(layoutController.layout);
  const columnsByKey = new Map(columns.map((column) => [column.key, column]));
  const tableWidth = visibleColumns.reduce((total, column) => total + column.width, actionWidth);

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
      <tbody>{rows.map((row) => <tr key={row.id}>
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
  return {
    canWrite: admin || session.permissions.includes("CUSTOMERS_WRITE"),
    canSetCategory: admin
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

export function MemberLoyaltyPanel({ app = "venta", memberId, session, t = fallback, request = apiRequest }: MemberLoyaltyPanelProps) {
  const [tab, setTab] = useState<Tab>("detail");
  const [visitedTabs, setVisitedTabs] = useState<Set<Tab>>(() => new Set(["detail"]));
  const [member, setMember] = useState<MemberView | null>(null);
  const [movements, setMovements] = useState<MemberMovement[]>([]);
  const [categories, setCategories] = useState<MemberCategory[]>([]);
  const [settings, setSettings] = useState<MemberSettings | null>(null);
  const [channels, setChannels] = useState<CommercialChannel[]>([]);
  const [deliveries, setDeliveries] = useState<MemberCardDelivery[]>([]);
  const [categoryDraft, setCategoryDraft] = useState({ id: "", name: "", minPoints: "0", discountPercent: "0", discountEnabled: true, sortOrder: "0" });
  const [channelDraft, setChannelDraft] = useState({ id: "", code: "", name: "", active: true });
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
    accessToken: visitedTabs.has("detail") ? session.accessToken : undefined,
    tableKey: memberLoyaltyTableKeys.movements,
    definitions: memberMovementColumnDefinitions
  });
  const categoryTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: visitedTabs.has("categories") ? session.accessToken : undefined,
    tableKey: memberLoyaltyTableKeys.categories,
    definitions: memberCategoryColumnDefinitions
  });
  const channelTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: visitedTabs.has("channels") ? session.accessToken : undefined,
    tableKey: memberLoyaltyTableKeys.channels,
    definitions: commercialChannelColumnDefinitions
  });
  const deliveryTableLayout = useTableLayoutPreference({
    app,
    username: session.username,
    accessToken: visitedTabs.has("deliveries") ? session.accessToken : undefined,
    tableKey: memberLoyaltyTableKeys.deliveries,
    definitions: memberCardDeliveryColumnDefinitions
  });

  function selectTab(nextTab: Tab) {
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
    if (tab === "settings" && !settings) void request<MemberSettings>("/member-settings", options).then(setSettings).catch(showError);
    if (tab === "channels" && channels.length === 0) void request<CommercialChannel[]>("/commercial-contact-channels", options).then(setChannels).catch(showError);
    if (tab === "deliveries" && deliveries.length === 0) void request<MemberCardDelivery[]>("/member-card-deliveries", options).then(setDeliveries).catch(showError);
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

  async function saveSettings(event: React.FormEvent) {
    event.preventDefault();
    if (!settings) return;
    await mutate(async () => setSettings(await request<MemberSettings>("/member-settings", { ...options, method: "PUT", body: settings })), false);
  }

  async function saveCategory(event: React.FormEvent) {
    event.preventDefault();
    const body = {
      name: categoryDraft.name.trim(), minPoints: Number(categoryDraft.minPoints) || 0,
      discountPercent: Number(categoryDraft.discountPercent) || 0, discountEnabled: categoryDraft.discountEnabled,
      sortOrder: Number(categoryDraft.sortOrder) || 0
    };
    if (!body.name) { setError("party.members.categoryNameRequired"); return; }
    await mutate(async () => {
      const saved = await request<MemberCategory>(categoryDraft.id ? `/member-categories/${categoryDraft.id}` : "/member-categories", {
        ...options, method: categoryDraft.id ? "PUT" : "POST", body
      });
      setCategories((rows) => categoryDraft.id ? rows.map((row) => row.id === saved.id ? saved : row) : [...rows, saved]);
      setCategoryDraft({ id: "", name: "", minPoints: "0", discountPercent: "0", discountEnabled: true, sortOrder: "0" });
    }, false);
  }

  async function deactivateCategory(id: string) {
    await mutate(async () => {
      await request(`/member-categories/${id}/deactivate`, { ...options, method: "PATCH" });
      setCategories((rows) => rows.map((row) => row.id === id ? { ...row, active: false } : row));
    }, false);
  }

  async function saveChannel(event: React.FormEvent) {
    event.preventDefault();
    const body = { code: channelDraft.code.trim().toUpperCase(), name: channelDraft.name.trim(), active: channelDraft.active };
    if (!body.code || !body.name) { setError("party.members.channelFieldsRequired"); return; }
    await mutate(async () => {
      const saved = await request<CommercialChannel>(channelDraft.id ? `/commercial-contact-channels/${channelDraft.id}` : "/commercial-contact-channels", {
        ...options, method: channelDraft.id ? "PUT" : "POST", body
      });
      setChannels((rows) => channelDraft.id ? rows.map((row) => row.id === saved.id ? saved : row) : [...rows, saved]);
      setChannelDraft({ id: "", code: "", name: "", active: true });
    }, false);
  }

  const movementColumns: readonly OperationalColumn<MemberMovementColumnKey, MemberMovement>[] = [
    { key: "date", label: t("party.members.date"), render: (item) => new Date(item.createdAt).toLocaleString() },
    { key: "movement", label: t("party.members.movement"), render: (item) => item.type },
    { key: "amount", label: t("party.members.amount"), render: (item) => item.pointsAmount || String(item.balanceAmount) },
    { key: "reason", label: t("party.members.reason"), render: (item) => item.reason || "—" }
  ];
  const categoryColumns: readonly OperationalColumn<MemberCategoryColumnKey, MemberCategory>[] = [
    { key: "code", label: t("party.code"), render: (item) => item.code },
    { key: "name", label: t("party.name"), render: (item) => item.name },
    { key: "minPoints", label: t("party.members.minPoints"), render: (item) => item.minPoints },
    { key: "discount", label: t("party.members.discount"), render: (item) => `${String(item.discountPercent)}%` },
    { key: "status", label: t("party.status"), render: (item) => t(item.active ? "party.active" : "party.inactive") }
  ];
  const channelColumns: readonly OperationalColumn<CommercialChannelColumnKey, CommercialChannel>[] = [
    { key: "code", label: t("party.code"), render: (item) => item.code },
    { key: "name", label: t("party.name"), render: (item) => item.name },
    { key: "status", label: t("party.status"), render: (item) => t(item.active ? "party.active" : "party.inactive") }
  ];
  const deliveryColumns: readonly OperationalColumn<MemberCardDeliveryColumnKey, MemberCardDelivery>[] = [
    { key: "email", label: t("party.email"), render: (item) => item.email },
    { key: "status", label: t("party.status"), render: (item) => item.status },
    { key: "date", label: t("party.members.date"), render: (item) => new Date(item.createdAt).toLocaleString() }
  ];
  const tabs: Tab[] = ["detail", "categories", "settings", "channels", "deliveries"];
  return <section className="stock-section party-member-loyalty" aria-label={t("party.members.loyaltyTitle")}>
    <div className="stock-section__header">
      <h3>{t("party.members.loyaltyTitle")}</h3>
      <div className="stock-toolbar" role="tablist">{tabs.map((item) =>
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
        <Summary label={t("party.status")} value={t(member.active ? "party.active" : "party.inactive")} />
      </div>
      {permissions.canSetCategory && <form className="stock-toolbar" onSubmit={(event) => {
        event.preventDefault();
        if (!categoryReason.trim()) { setError("party.members.reasonRequired"); return; }
        void mutate(() => request(`/members/${memberId}/category`, { ...options, method: "PUT", body: { categoryId: categoryId || null, lockAutomatic: member.autoCategoryLocked, reason: categoryReason.trim() } }));
      }}>
        <label>{t("party.members.category")}<select value={categoryId} onChange={(event) => setCategoryId(event.target.value)}><option value="">—</option>{categories.filter((item) => item.active).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>{t("party.members.reason")}<input value={categoryReason} onChange={(event) => setCategoryReason(event.target.value)} /></label>
        <button disabled={busy}>{t("common.save")}</button>
      </form>}
      {permissions.canWrite && <form className="stock-toolbar" onSubmit={submitAdjustment}>
        <select aria-label={t("party.members.adjustmentType")} value={adjustment.kind} onChange={(event) => setAdjustment((value) => ({ ...value, kind: event.target.value as "points" | "balance" }))}><option value="points">{t("party.members.points")}</option><option value="balance">{t("party.members.balance")}</option></select>
        <input aria-label={t("party.members.amount")} type="number" step={adjustment.kind === "balance" ? "0.01" : "1"} value={adjustment.value} onChange={(event) => setAdjustment((value) => ({ ...value, value: event.target.value }))} />
        <input aria-label={t("party.members.reason")} value={adjustment.reason} onChange={(event) => setAdjustment((value) => ({ ...value, reason: event.target.value }))} />
        <button disabled={busy}>{t("party.members.adjust")}</button>
      </form>}
      <MemberOperationalTable
        columns={movementColumns}
        layoutController={movementTableLayout}
        rows={movements}
        resizeLabel={t("stock.columns.resize")}
      />
    </>}

    {tab === "categories" && <>
      {permissions.canWrite && <form className="party-member-admin-form" onSubmit={saveCategory}>
        <label>{t("party.name")}<input value={categoryDraft.name} onChange={(event) => setCategoryDraft({ ...categoryDraft, name: event.target.value })} /></label>
        <label>{t("party.members.minPoints")}<input type="number" min="0" value={categoryDraft.minPoints} onChange={(event) => setCategoryDraft({ ...categoryDraft, minPoints: event.target.value })} /></label>
        <label>{t("party.members.discount")}<input type="number" min="0" max="100" step="0.01" value={categoryDraft.discountPercent} onChange={(event) => setCategoryDraft({ ...categoryDraft, discountPercent: event.target.value })} /></label>
        <label>{t("party.members.sortOrder")}<input type="number" value={categoryDraft.sortOrder} onChange={(event) => setCategoryDraft({ ...categoryDraft, sortOrder: event.target.value })} /></label>
        <label className="party-member-check"><input type="checkbox" checked={categoryDraft.discountEnabled} onChange={(event) => setCategoryDraft({ ...categoryDraft, discountEnabled: event.target.checked })} />{t("party.members.discountEnabled")}</label>
        <button disabled={busy}>{t(categoryDraft.id ? "party.members.updateCategory" : "party.members.createCategory")}</button>
      </form>}
      <MemberOperationalTable
        columns={categoryColumns}
        layoutController={categoryTableLayout}
        rows={categories}
        resizeLabel={t("stock.columns.resize")}
        actionLabel={t("common.actions")}
        actionWidth={220}
        renderActions={(item) => permissions.canWrite && <div className="party-member-row-actions"><button type="button" onClick={() => setCategoryDraft({ id: item.id, name: item.name, minPoints: String(item.minPoints), discountPercent: String(item.discountPercent), discountEnabled: item.discountEnabled, sortOrder: String(item.sortOrder) })}>{t("party.members.edit")}</button>{item.active && <button type="button" onClick={() => void deactivateCategory(item.id)}>{t("party.action.deactivate")}</button>}</div>}
      />
    </>}

    {tab === "settings" && settings && <form className="party-form" onSubmit={saveSettings}>
      <NumberField label={t("party.members.balanceAccrualPercent")} value={settings.balanceAccrualPercent} disabled={!permissions.canWrite} onChange={(value) => setSettings({ ...settings, balanceAccrualPercent: value })} />
      <NumberField label={t("party.members.pointsPerEuro")} value={settings.pointsPerEuro} disabled={!permissions.canWrite} onChange={(value) => setSettings({ ...settings, pointsPerEuro: value })} />
      <label>{t("party.members.expirationPolicy")}<select disabled={!permissions.canWrite} value={settings.balanceExpirationPolicy} onChange={(event) => setSettings({ ...settings, balanceExpirationPolicy: event.target.value })}>{["NO_CADUCA", "UN_MES", "TRES_MESES", "SEIS_MESES", "UN_ANO"].map((value) => <option key={value} value={value}>{t(`party.members.expiration.${value}`)}</option>)}</select></label>
      <label><input type="checkbox" disabled={!permissions.canWrite} checked={settings.categoryAutoEnabled} onChange={(event) => setSettings({ ...settings, categoryAutoEnabled: event.target.checked })} /> {t("party.members.autoCategories")}</label>
      <label><input type="checkbox" disabled={!permissions.canWrite} checked={settings.memberWelcomeEnabled} onChange={(event) => setSettings({ ...settings, memberWelcomeEnabled: event.target.checked })} /> {t("party.members.welcomeEnabled")}</label>
      <label>{t("party.members.cardFormat")}<select disabled={!permissions.canWrite} value={settings.memberCardCodeFormat} onChange={(event) => setSettings({ ...settings, memberCardCodeFormat: event.target.value as "QR" | "BARCODE" })}><option value="QR">QR</option><option value="BARCODE">BARCODE</option></select></label>
      <label>{t("party.members.welcomeSubject")}<input disabled={!permissions.canWrite} value={settings.welcomeSubjectTemplate ?? ""} onChange={(event) => setSettings({ ...settings, welcomeSubjectTemplate: event.target.value })} /></label>
      <label className="party-member-wide">{t("party.members.welcomeBody")}<textarea disabled={!permissions.canWrite} value={settings.welcomeBodyTemplate ?? ""} onChange={(event) => setSettings({ ...settings, welcomeBodyTemplate: event.target.value })} /></label>
      {permissions.canWrite && <button disabled={busy}>{t("common.save")}</button>}
    </form>}

    {tab === "channels" && <>
      {permissions.canWrite && <form className="party-member-admin-form" onSubmit={saveChannel}><label>{t("party.code")}<input value={channelDraft.code} onChange={(event) => setChannelDraft({ ...channelDraft, code: event.target.value })} /></label><label>{t("party.name")}<input value={channelDraft.name} onChange={(event) => setChannelDraft({ ...channelDraft, name: event.target.value })} /></label><label className="party-member-check"><input type="checkbox" checked={channelDraft.active} onChange={(event) => setChannelDraft({ ...channelDraft, active: event.target.checked })} />{t("party.active")}</label><button disabled={busy}>{t(channelDraft.id ? "party.members.updateChannel" : "party.members.createChannel")}</button></form>}
      <MemberOperationalTable
        columns={channelColumns}
        layoutController={channelTableLayout}
        rows={channels}
        resizeLabel={t("stock.columns.resize")}
        actionLabel={t("common.actions")}
        actionWidth={120}
        renderActions={(item) => permissions.canWrite && <button type="button" onClick={() => setChannelDraft({ id: item.id, code: item.code, name: item.name, active: item.active })}>{t("party.members.edit")}</button>}
      />
    </>}

    {tab === "deliveries" && <MemberOperationalTable
      columns={deliveryColumns}
      layoutController={deliveryTableLayout}
      rows={deliveries}
      resizeLabel={t("stock.columns.resize")}
      actionLabel={t("common.actions")}
      actionWidth={120}
      renderActions={(item) => permissions.canWrite && ["FAILED", "PENDING"].includes(item.status) && <button disabled={busy} onClick={() => void mutate(async () => {
        const updated = await request<MemberCardDelivery>(`/member-card-deliveries/${item.id}/retry`, { ...options, method: "PATCH" });
        setDeliveries((rows) => rows.map((row) => row.id === updated.id ? updated : row));
      }, false)}>{t("party.members.retry")}</button>}
    />}
  </section>;
}

function Summary({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><strong>{value}</strong></div>; }
function NumberField({ label, value, disabled, onChange }: { label: string; value: number | string; disabled: boolean; onChange: (value: string) => void }) {
  return <label>{label}<input type="number" step="0.01" disabled={disabled} value={value} onChange={(event) => onChange(event.target.value)} /></label>;
}
