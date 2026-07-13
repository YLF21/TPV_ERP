import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import type { StockBulkDraftView } from "./stockBulkEdit";

type StockBulkWorkspaceListProps = {
  locale: LocaleCode;
  session: UserSession;
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
          <thead>
            <tr>
              <th>{t("stock.bulkEdit.workspace.code")}</th>
              <th>{t("stock.bulkEdit.listName")}</th>
              <th>{t("stock.bulkEdit.workspace.version")}</th>
              <th>{t("stock.bulkEdit.draft.comment")}</th>
              <th>{t("stock.bulkEdit.draft.createdBy")}</th>
              <th>{t("stock.bulkEdit.draft.createdAt")}</th>
              <th>{t("stock.bulkEdit.draft.updatedBy")}</th>
              <th>{t("stock.bulkEdit.draft.updatedAt")}</th>
              <th>{t("stock.bulkEdit.workspace.status")}</th>
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
                  <td><strong>{draft.code}</strong></td>
                  <td>{draft.name}</td>
                  <td>V{draft.versionNumber}</td>
                  <td className="stock-bulk-workspace-comment" title={latestComment}>{latestComment}</td>
                  <td>{draft.createdBy}</td>
                  <td>{dateFormatter.format(new Date(draft.createdAt))}</td>
                  <td>{draft.updatedBy}</td>
                  <td>{dateFormatter.format(new Date(draft.updatedAt))}</td>
                  <td><span className={`stock-bulk-workspace-status ${draft.status.toLowerCase()}`}>{t(`stock.bulkEdit.status.${draft.status}`)}</span></td>
                </tr>
              );
            })}
            {drafts.length === 0 && (
              <tr>
                <td colSpan={9} className="stock-empty-state">{t("stock.bulkEdit.noDrafts")}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
