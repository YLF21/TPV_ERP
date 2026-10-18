import { useEffect, useRef, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import { PromotionWizard, type PromotionView } from "./PromotionWizard";
import "./PromotionManagement.css";

export type PromotionSelectionRequest = { id: string; key: number };
export type PromotionManagementActions = {
  busy: boolean;
  onEditConditions: (promotion: PromotionView) => void;
  onEditProducts: (promotion: PromotionView) => void;
  onToggleActive: (promotion: PromotionView) => void;
};

export function usePromotionManagement({ enabled, locale, session, onSaved, onRefresh }: {
  enabled: boolean;
  locale: LocaleCode;
  session: UserSession;
  onSaved: (promotion: PromotionView) => void;
  onRefresh: () => void;
}) {
  const t = createTranslator(locale);
  const [editor, setEditor] = useState<{ promotion?: PromotionView; section: "basic" | "scope"; epoch: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState(false);
  const [selectionRequest, setSelectionRequest] = useState<PromotionSelectionRequest>();
  const pending = useRef(false);
  const epoch = useRef(0);
  const selectionKey = useRef(0);
  const returnFocus = useRef<HTMLElement | null>(null);

  useEffect(() => {
    ++epoch.current;
    setEditor(null);
    setNotice("");
    setBusy(false);
    pending.current = false;
    return () => { ++epoch.current; };
  }, [enabled, session.accessToken]);

  function openEditor(promotion?: PromotionView, section: "basic" | "scope" = "basic") {
    if (!enabled || pending.current) return;
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setNotice("");
    setEditor({ promotion, section, epoch: epoch.current });
  }

  function closeEditor() {
    setEditor(null);
    requestAnimationFrame(() => returnFocus.current?.focus({ preventScroll: true }));
  }

  useEffect(() => {
    if (!enabled || editor) return;
    const handleCreate = (event: globalThis.KeyboardEvent) => {
      if (event.defaultPrevented || event.key !== "F5" || event.repeat || event.ctrlKey
          || event.altKey || event.metaKey || event.shiftKey || document.querySelector('[aria-modal="true"]')) return;
      event.preventDefault();
      openEditor();
    };
    window.addEventListener("keydown", handleCreate);
    return () => window.removeEventListener("keydown", handleCreate);
  }, [enabled, editor]);

  async function toggleActive(promotion: PromotionView) {
    if (!enabled || pending.current || !session.accessToken) return;
    const requestEpoch = epoch.current;
    const action = promotion.status === "ACTIVE" ? "deactivate" : "activate";
    pending.current = true;
    setBusy(true);
    setNotice("");
    try {
      const saved = await apiRequest<PromotionView>(`/promotions/${encodeURIComponent(promotion.id)}/${action}`, {
        token: session.accessToken, method: "POST"
      });
      if (requestEpoch !== epoch.current) return;
      onSaved(saved);
      setSelectionRequest({ id: saved.id, key: ++selectionKey.current });
      setError(false);
      setNotice(action === "activate" ? "promotion.status.activated" : "promotion.status.deactivated");
      onRefresh();
    } catch {
      if (requestEpoch === epoch.current) {
        setError(true);
        setNotice(`promotion.status.${action}Error`);
      }
    } finally {
      if (requestEpoch === epoch.current) {
        pending.current = false;
        setBusy(false);
      }
    }
  }

  const actions: PromotionManagementActions | undefined = enabled ? {
    busy,
    onEditConditions: (promotion) => openEditor(promotion),
    onEditProducts: (promotion) => openEditor(promotion, "scope"),
    onToggleActive: (promotion) => { void toggleActive(promotion); }
  } : undefined;

  const editorElement = enabled && editor ? <div className="filter-overlay stock-promotion-editor-overlay"
    role="dialog" aria-modal="true" aria-labelledby="promotion-create-title"
    onKeyDown={event => {
      if (event.key !== "Escape" || event.defaultPrevented
          || event.currentTarget.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled
          || (event.target as HTMLElement).closest('[role="dialog"]') !== event.currentTarget) return;
      event.preventDefault();
      closeEditor();
    }}>
    <section className="stock-promotion-editor">
      <PromotionWizard key={editor.promotion?.id ?? "create"} locale={locale} session={session}
        editPromotion={editor.promotion} initialSection={editor.section} onClose={closeEditor}
        onCreated={saved => {
          if (editor.epoch !== epoch.current) return;
          onSaved(saved);
          setSelectionRequest({ id: saved.id, key: ++selectionKey.current });
          setError(false);
          setNotice(!editor.promotion ? "promotion.status.created"
            : saved.id !== editor.promotion.id ? "promotion.status.savedDraft" : "promotion.status.updated");
          closeEditor();
          onRefresh();
        }} />
    </section>
  </div> : null;

  return { actions, editorElement, selectionRequest, openCreate: () => openEditor(),
    notice: enabled && notice ? <p className="promotion-management-notice" role={error ? "alert" : "status"}>{t(notice)}</p> : null };
}
