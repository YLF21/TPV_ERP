import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ApiError,
  ErpSelect,
  TableLayoutHeaderCell,
  apiRequest,
  tableLayoutGridTemplate,
  useTableLayoutPreference,
  visibleTableColumns,
  type UserSession
} from "@tpverp/app-common";
import "./MemberCategoriesScreen.css";

type Translate = (key: string) => string;
type Request = typeof apiRequest;
type Category = {
  id: string; code: string; name: string; minPoints: number;
  discountPercent: number | string; discountEnabled: boolean; manualOnly: boolean;
  active: boolean; sortOrder: number;
};
type Draft = {
  id: string; name: string; minPoints: string; discountPercent: string;
  discountEnabled: boolean; manualOnly: boolean;
};
type StatusFilter = "all" | "active" | "inactive";
type CategoryColumnKey = "code" | "name" | "type" | "minPoints" | "discount" | "status";

const categoryColumnDefinitions = [
  { key: "code", defaultWidth: 130 },
  { key: "name", defaultWidth: 250 },
  { key: "type", defaultWidth: 170 },
  { key: "minPoints", defaultWidth: 120 },
  { key: "discount", defaultWidth: 110 },
  { key: "status", defaultWidth: 96 }
] as const;

const emptyDraft: Draft = {
  id: "", name: "", minPoints: "0", discountPercent: "0",
  discountEnabled: false, manualOnly: false
};

function sortCategories(rows: Category[]) {
  return [...rows].sort((left, right) => Number(left.manualOnly) - Number(right.manualOnly)
    || left.minPoints - right.minPoints || left.name.localeCompare(right.name));
}

export function MemberCategoriesScreen({ session, t, request = apiRequest }: {
  session: UserSession; t: Translate; request?: Request;
}) {
  const [categories, setCategories] = useState<Category[]>([]);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<StatusFilter>("all");
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState("");
  const canManage = session.permissions.includes("ADMIN");
  const options = useMemo(() => ({ token: session.accessToken }), [session.accessToken]);
  const tableLayout = useTableLayoutPreference<CategoryColumnKey>({
    app: "gestion",
    username: session.username,
    accessToken: session.accessToken,
    tableKey: "gestion.memberCategories",
    definitions: categoryColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const gridStyle = {
    gridTemplateColumns: `${tableLayoutGridTemplate(tableLayout.layout)} 190px`
  };

  const message = useCallback((cause: unknown, fallback: string) => {
    if (!(cause instanceof Error)) {
      return t(fallback);
    }

    if (cause instanceof ApiError) {
      const code = typeof cause.problem?.code === "string" ? cause.problem.code : "";
      const detail = typeof cause.problem?.detail === "string" ? cause.problem.detail : "";

      if (code && t(code) !== code) {
        return t(code);
      }
      if (detail && t(detail) !== detail) {
        return t(detail);
      }
      if (detail) {
        return detail;
      }
    }

    return cause.message && cause.message !== "api_error" ? cause.message : t(fallback);
  }, [t]);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setCategories(sortCategories(await request<Category[]>("/member-categories", options))); }
    catch (cause) { setError(message(cause, "gestion.memberCategories.loadError")); }
    finally { setLoading(false); }
  }, [message, options, request]);

  useEffect(() => { void load(); }, [load]);

  const normalizedQuery = query.trim().toLocaleLowerCase();
  const visible = categories.filter((category) => (filter === "all"
    || (filter === "active" ? category.active : !category.active))
    && (!normalizedQuery || category.name.toLocaleLowerCase().includes(normalizedQuery)
      || category.code.toLocaleLowerCase().includes(normalizedQuery)));

  function columnLabel(key: CategoryColumnKey) {
    if (key === "code") return t("party.code");
    if (key === "name") return t("party.name");
    if (key === "type") return t("gestion.memberCategories.type");
    if (key === "minPoints") return t("party.members.minPoints");
    if (key === "discount") return t("party.members.discount");
    return t("party.status");
  }

  function renderCell(key: CategoryColumnKey, category: Category) {
    const className = `party-directory-cell member-category-cell member-category-cell-${key}`;
    if (key === "code") return <code className={className} data-column-key={key} key={key}>{category.code}</code>;
    if (key === "name") return <strong className={className} data-column-key={key} key={key}>{category.name}</strong>;
    if (key === "type") return <span className={className} data-column-key={key} key={key}>{t(category.manualOnly ? "gestion.memberCategories.type.manual" : "gestion.memberCategories.type.automatic")}</span>;
    if (key === "minPoints") return <span className={className} data-column-key={key} key={key}>{category.manualOnly ? t("gestion.memberCategories.notApplicable") : category.minPoints}</span>;
    if (key === "discount") return <span className={className} data-column-key={key} key={key}>{category.discountEnabled ? `${category.discountPercent} %` : t("gestion.memberCategories.discountDisabled")}</span>;
    return <span className={`${className} party-status ${category.active ? "active" : ""}`} data-column-key={key} key={key}>{t(category.active ? "party.active" : "party.inactive")}</span>;
  }

  function openNew() {
    setDraft(emptyDraft); setError(""); setFeedback(""); setDialogOpen(true);
  }

  function openEdit(category: Category) {
    setDraft({ id: category.id, name: category.name, minPoints: String(category.minPoints),
      discountPercent: String(category.discountPercent), discountEnabled: category.discountEnabled,
      manualOnly: category.manualOnly });
    setError(""); setFeedback(""); setDialogOpen(true);
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    const minPoints = Number(draft.minPoints);
    const discountPercent = Number(draft.discountPercent);
    if (!draft.name.trim() || !Number.isInteger(minPoints) || minPoints < 0
      || !Number.isFinite(discountPercent) || discountPercent < 0 || discountPercent > 100
    ) {
      setError(t("gestion.memberCategories.invalid")); return;
    }
    setBusy(true); setError("");
    try {
      const saved = await request<Category>(draft.id ? `/member-categories/${draft.id}` : "/member-categories", {
        ...options, method: draft.id ? "PUT" : "POST",
        body: { name: draft.name.trim(), minPoints: draft.manualOnly ? 0 : minPoints,
          discountPercent, discountEnabled: draft.discountEnabled, manualOnly: draft.manualOnly,
          sortOrder: draft.manualOnly ? 2_000_000_000 : minPoints }
      });
      setCategories((current) => sortCategories(draft.id
        ? current.map((category) => category.id === saved.id ? saved : category) : [...current, saved]));
      setDialogOpen(false); setFeedback(t("gestion.memberCategories.saved"));
    } catch (cause) { setError(message(cause, "gestion.memberCategories.saveError")); }
    finally { setBusy(false); }
  }

  async function toggle(category: Category) {
    const action = category.active ? "deactivate" : "activate";
    if (!window.confirm(t(`gestion.memberCategories.confirm.${action}`))) return;
    setBusy(true); setError(""); setFeedback("");
    try {
      if (category.active) {
        await request<void>(`/member-categories/${category.id}/deactivate`, { ...options, method: "PATCH" });
        setCategories((current) => current.map((item) => item.id === category.id ? { ...item, active: false } : item));
      } else {
        const activated = await request<Category>(`/member-categories/${category.id}/activate`, { ...options, method: "PATCH" });
        setCategories((current) => sortCategories(current.map((item) => item.id === category.id ? activated : item)));
      }
      setFeedback(t("gestion.memberCategories.saved"));
    } catch (cause) { setError(message(cause, "gestion.memberCategories.saveError")); }
    finally { setBusy(false); }
  }

  const filtersActive = Boolean(query || filter !== "all");

  return <section className="member-categories-screen">
    <header className="work-panel-heading stock-panel-heading party-directory-heading">
      <div><h2>{t("gestion.memberCategories.title")}</h2><span>{t("gestion.memberCategories.subtitle")}</span></div>
      {canManage && <button type="button" className="stock-add-product-button" disabled={busy} onClick={openNew}>{t("gestion.memberCategories.new")}</button>}
    </header>

    <div className="party-directory-toolbar">
      <input aria-label={t("gestion.memberCategories.search")} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("gestion.memberCategories.search")} />
      <label className="party-directory-status-filter"><span>{t("party.status")}</span><ErpSelect className="erp-select--compact" value={filter} aria-label={t("party.status")} onChange={(value) => setFilter(value as StatusFilter)} options={["all", "active", "inactive"].map((value) => ({ value, label: t(value === "all" ? "gestion.memberCategories.filter.all" : `party.${value}`) }))} /></label>
      {filtersActive && <button type="button" className="party-directory-clear-filters" onClick={() => { setQuery(""); setFilter("all"); }}>{t("party.filter.clear")}</button>}
      <span className="party-directory-result-count">{t("party.results").replace("{count}", String(visible.length))}</span>
      {filtersActive && <div className="party-directory-active-filters" aria-label={t("party.filter.active")}>
        {query && <button type="button" onClick={() => setQuery("")}>{t("party.searchLabel")}: {query}<span aria-hidden="true"> ×</span></button>}
        {filter !== "all" && <button type="button" onClick={() => setFilter("all")}>{t("party.status")}: {t(`party.${filter}`)}<span aria-hidden="true"> ×</span></button>}
      </div>}
    </div>

    <div className="party-directory-table member-categories-table" role="table" aria-label={t("gestion.memberCategories.title")}>
      <div className="party-directory-row member-category-row header" role="row" style={gridStyle}>
        {visibleColumns.map((column) => <TableLayoutHeaderCell as="span" column={column} key={column.key} resizeLabel={`${t("stock.columns.resize")} ${columnLabel(column.key)}`} onReorder={tableLayout.reorderColumns} onMove={tableLayout.moveColumn} onResize={tableLayout.resizeColumn}>{columnLabel(column.key)}</TableLayoutHeaderCell>)}
        <span className="member-category-actions-header" role="columnheader">{t("common.actions")}</span>
      </div>
      {loading && <div className="stock-empty-state">{t("common.loading")}</div>}
      {!loading && error && !dialogOpen && <div className="party-directory-state error" role="alert"><span>{error}</span><button type="button" onClick={() => void load()}>{t("party.retry")}</button></div>}
      {!loading && !error && visible.map((category) => <div className="party-directory-row member-category-row" role="row" style={gridStyle} key={category.id}>
        {visibleColumns.map((column) => renderCell(column.key, category))}
        <div className="party-member-row-actions member-category-row-actions"><button type="button" disabled={busy || !canManage} onClick={() => openEdit(category)}>{t("party.members.edit")}</button><button type="button" disabled={busy || !canManage} onClick={() => void toggle(category)}>{t(category.active ? "party.action.deactivate" : "party.action.activate")}</button></div>
      </div>)}
      {!loading && !error && visible.length === 0 && <div className="party-directory-state"><span>{t("gestion.memberCategories.empty")}</span>{canManage && <button type="button" onClick={openNew}>{t("gestion.memberCategories.new")}</button>}</div>}
    </div>
    {feedback && !dialogOpen && <p className="product-create-status party-directory-toast" role="status">{feedback}</p>}

    {dialogOpen && <div className="filter-overlay" role="dialog" aria-modal="true" aria-labelledby="member-category-dialog-title">
      <section className="filter-dialog product-create-dialog party-create-dialog member-category-dialog">
        <header className="filter-header"><div><h2 id="member-category-dialog-title">{t(draft.id ? "gestion.memberCategories.editTitle" : "gestion.memberCategories.newTitle")}</h2><span>{t("gestion.memberCategories.formHint")}</span></div><button type="button" disabled={busy} onClick={() => setDialogOpen(false)}>{t("common.close")}</button></header>
        <form className="product-create-form party-create-form member-category-form" onSubmit={(event) => void save(event)}>
          <fieldset disabled={busy || !canManage}>
            <label className="filter-field"><span>{t("party.name")}</span><input autoFocus maxLength={64} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
            <label className="filter-field"><span>{t("party.members.minPoints")}</span><input type="number" min="0" step="1" disabled={draft.manualOnly} value={draft.manualOnly ? "0" : draft.minPoints} onChange={(event) => setDraft({ ...draft, minPoints: event.target.value })} /></label>
            <label className="filter-field"><span>{t("gestion.memberCategories.discountPercent")}</span><input type="number" min="0" max="100" step="0.01" value={draft.discountPercent} onChange={(event) => setDraft({ ...draft, discountPercent: event.target.value })} /></label>
            <label className="product-create-check"><input type="checkbox" checked={draft.discountEnabled} onChange={(event) => setDraft({ ...draft, discountEnabled: event.target.checked })} /><span>{t("party.members.discountEnabled")}</span></label>
            <label className="product-create-check member-category-manual-check"><input type="checkbox" checked={draft.manualOnly} onChange={(event) => setDraft({ ...draft, manualOnly: event.target.checked })} /><span><strong>{t("gestion.memberCategories.manualOnly")}</strong><small>{t("gestion.memberCategories.manualOnlyHint")}</small></span></label>
          </fieldset>
          {error && <p className="product-create-status" role="alert">{error}</p>}
          <footer className="filter-actions"><button type="button" disabled={busy} onClick={() => setDialogOpen(false)}>{t("common.cancel")}</button><button type="submit" disabled={busy || !canManage}>{busy ? t("common.saving") : t("common.save")}</button></footer>
        </form>
      </section>
    </div>}
  </section>;
}
