import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, UserSession } from "../types";
import type { StockBulkDraftView } from "./stockBulkEdit";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

const stockBulkWorkspaceColumns = [
  { key: "code", labelKey: "stock.bulkEdit.workspace.code", defaultWidth: 128 },
  { key: "name", labelKey: "stock.bulkEdit.listName", defaultWidth: 190 },
  { key: "version", labelKey: "stock.bulkEdit.workspace.version", defaultWidth: 72 },
  { key: "comment", labelKey: "stock.bulkEdit.draft.comment", defaultWidth: 240 },
  { key: "createdBy", labelKey: "stock.bulkEdit.draft.createdBy", defaultWidth: 120 },
  { key: "createdAt", labelKey: "stock.bulkEdit.draft.createdAt", defaultWidth: 148 },
  { key: "updatedBy", labelKey: "stock.bulkEdit.draft.updatedBy", defaultWidth: 120 },
  { key: "updatedAt", labelKey: "stock.bulkEdit.draft.updatedAt", defaultWidth: 148 },
  { key: "status", labelKey: "stock.bulkEdit.workspace.status", defaultWidth: 104 }
] as const;

type StockBulkWorkspaceColumnKey = typeof stockBulkWorkspaceColumns[number]["key"];

const stockBulkWorkspaceColumnByKey = new Map(
  stockBulkWorkspaceColumns.map((column) => [column.key, column] as const)
);

type StockBulkWorkspaceListProps = {
  locale: LocaleCode;
  session: UserSession;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  drafts: StockBulkDraftView[];
  selectedId: string | null;
  busy: boolean;
  onSelect: (id: string) => void;
  onNew: () => void;
  onOpen: (draft: StockBulkDraftView) => void;
  onComments: (draft: StockBulkDraftView) => void;
  onRename: (draft: StockBulkDraftView) => void;
  onDelete: (draft: StockBulkDraftView) => void;
};

export function nextStockBulkDraftId(
  drafts: readonly Pick<StockBulkDraftView, "id">[],
  selectedId: string | null,
  direction: -1 | 1
) {
  if (drafts.length === 0) return null;
  const currentIndex = drafts.findIndex((draft) => draft.id === selectedId);
  if (currentIndex < 0) return direction > 0 ? drafts[0].id : drafts.at(-1)?.id ?? null;
  return drafts[Math.min(drafts.length - 1, Math.max(0, currentIndex + direction))].id;
}

export function canDeleteStockBulkDraft(draft: StockBulkDraftView, session: UserSession) {
  return session.permissions.includes("ADMIN")
    || Boolean(session.userId && session.userId === draft.createdById)
    || [session.username, session.displayName]
      .some((name) => name.localeCompare(draft.createdBy, undefined, { sensitivity: "accent" }) === 0);
}

export function StockBulkWorkspaceList({
  locale,
  session,
  app = "venta",
  username = "",
  accessToken,
  drafts,
  selectedId,
  busy,
  onSelect,
  onNew,
  onOpen,
  onComments,
  onRename,
  onDelete
}: StockBulkWorkspaceListProps) {
  const t = createTranslator(locale);
  const tableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken,
    tableKey: "stock.bulkEdit.workspaces",
    definitions: stockBulkWorkspaceColumns
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  const selectedDraft = drafts.find((draft) => draft.id === selectedId) ?? null;
  const canDelete = selectedDraft ? canDeleteStockBulkDraft(selectedDraft, session) : false;
  const dateFormatter = new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", {
    dateStyle: "short",
    timeStyle: "short"
  });

  useEffect(() => {
    if (selectedId) rowRefs.current.get(selectedId)?.scrollIntoView({ block: "nearest" });
  }, [selectedId]);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.target !== event.currentTarget) {
      return;
    }
    if (event.key === "ArrowUp" || event.key === "ArrowDown") {
      event.preventDefault();
      const nextId = nextStockBulkDraftId(drafts, selectedId, event.key === "ArrowDown" ? 1 : -1);
      if (nextId) onSelect(nextId);
      return;
    }
    if (event.key === "Enter" && selectedDraft) {
      event.preventDefault();
      onOpen(selectedDraft);
    }
  }

  function renderDraftCell(
    draft: StockBulkDraftView,
    columnKey: StockBulkWorkspaceColumnKey,
    latestComment: string
  ) {
    switch (columnKey) {
      case "code":
        return <td key={columnKey} data-column-key={columnKey}><strong>{draft.code}</strong></td>;
      case "name":
        return <td key={columnKey} data-column-key={columnKey}>{draft.name}</td>;
      case "version":
        return <td key={columnKey} data-column-key={columnKey}>V{draft.versionNumber}</td>;
      case "comment":
        return (
          <td
            key={columnKey}
            data-column-key={columnKey}
            className="stock-bulk-workspace-comment"
            title={latestComment}
          >
            {latestComment}
          </td>
        );
      case "createdBy":
        return <td key={columnKey} data-column-key={columnKey}>{draft.createdBy}</td>;
      case "createdAt":
        return <td key={columnKey} data-column-key={columnKey}>{dateFormatter.format(new Date(draft.createdAt))}</td>;
      case "updatedBy":
        return <td key={columnKey} data-column-key={columnKey}>{draft.updatedBy}</td>;
      case "updatedAt":
        return <td key={columnKey} data-column-key={columnKey}>{dateFormatter.format(new Date(draft.updatedAt))}</td>;
      case "status":
        return (
          <td key={columnKey} data-column-key={columnKey}>
            <span className={`stock-bulk-workspace-status ${draft.status.toLowerCase()}`}>
              {t(`stock.bulkEdit.status.${draft.status}`)}
            </span>
          </td>
        );
    }
  }

  return (
    <section className="stock-bulk-workspace-list" aria-label={t("stock.bulkEdit.workspace.title") }>
      <header className="stock-bulk-workspace-toolbar">
        <button type="button" className="primary" disabled={busy} onClick={onNew}>
          {t("stock.bulkEdit.workspace.new")}
        </button>
        <button type="button" disabled={busy || !selectedDraft} onClick={() => selectedDraft && onOpen(selectedDraft)}>
          {t("stock.bulkEdit.workspace.open")}
        </button>
        <button type="button" disabled={busy || !selectedDraft} onClick={() => selectedDraft && onComments(selectedDraft)}>
          {t("stock.bulkEdit.comments")}
        </button>
        <button type="button" disabled={busy || !selectedDraft} onClick={() => selectedDraft && onRename(selectedDraft)}>
          {t("stock.bulkEdit.workspace.rename")}
        </button>
        <button
          type="button"
          className="danger"
          disabled={busy || !selectedDraft || !canDelete}
          title={selectedDraft && !canDelete ? t("stock.bulkEdit.deleteRestricted") : t("stock.bulkEdit.delete")}
          onClick={() => selectedDraft && onDelete(selectedDraft)}
        >
          {t("stock.bulkEdit.delete")}
        </button>
      </header>

      <div
        className="stock-bulk-workspace-table-wrap"
        autoFocus
        tabIndex={0}
        onKeyDown={handleKeyDown}
        aria-label={t("stock.bulkEdit.workspace.table")}
      >
        <table className="report-table stock-bulk-workspace-table">
          <colgroup>
            {visibleColumns.map((column) => <col key={column.key} style={{ width: column.width }} />)}
          </colgroup>
          <thead>
            <tr>
              {visibleColumns.map((column) => {
                const definition = stockBulkWorkspaceColumnByKey.get(column.key);
                const label = t(definition?.labelKey ?? column.key);
                return (
                  <TableLayoutHeaderCell
                    column={column}
                    key={column.key}
                    resizeLabel={`${t("stock.columns.resize")} ${label}`}
                    onReorder={tableLayout.reorderColumns}
                    onMove={tableLayout.moveColumn}
                    onResize={tableLayout.resizeColumn}
                  >
                    {label}
                  </TableLayoutHeaderCell>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {drafts.map((draft) => {
              const latestComment = draft.comments.at(-1)?.text || t("stock.bulkEdit.draft.noComment");
              return (
                <tr
                  key={draft.id}
                  ref={(element) => {
                    if (element) rowRefs.current.set(draft.id, element);
                    else rowRefs.current.delete(draft.id);
                  }}
                  className={draft.id === selectedId ? "selected" : ""}
                  aria-selected={draft.id === selectedId}
                  onClick={() => onSelect(draft.id)}
                  onDoubleClick={() => onOpen(draft)}
                >
                  {visibleColumns.map((column) => renderDraftCell(draft, column.key, latestComment))}
                </tr>
              );
            })}
            {drafts.length === 0 && (
              <tr>
                <td colSpan={visibleColumns.length} className="stock-empty-state">{t("stock.bulkEdit.noDrafts")}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
