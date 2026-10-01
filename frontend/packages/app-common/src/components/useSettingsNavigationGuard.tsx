import { useEffect, useRef, useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import { ErpConfirmDialog } from "./ErpConfirmDialog";

export type SettingsSaveResult = { ok: boolean; error?: string };

type Options = {
  dirty: boolean;
  saving?: boolean;
  locale: LocaleCode;
  save: () => Promise<SettingsSaveResult>;
  discard: () => void;
};

export function useSettingsNavigationGuard({ dirty, saving = false, locale, save, discard }: Options) {
  const pendingNavigation = useRef<(() => void) | null>(null);
  const savingRef = useRef(false);
  const mounted = useRef(false);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const t = createTranslator(locale);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  function requestNavigation(action: () => void) {
    if (saving || savingRef.current || pendingNavigation.current) return;
    if (!dirty) {
      action();
      return;
    }
    pendingNavigation.current = action;
    setError(undefined);
    setOpen(true);
  }

  function continueNavigation() {
    const action = pendingNavigation.current;
    pendingNavigation.current = null;
    setOpen(false);
    action?.();
  }

  function discardAndContinue() {
    if (saving || savingRef.current) return;
    discard();
    continueNavigation();
  }

  async function saveAndContinue() {
    if (saving || savingRef.current || !pendingNavigation.current) return;
    savingRef.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const result = await save();
      if (!mounted.current) return;
      if (result.ok) continueNavigation();
      else setError(result.error || t("settings.unsavedChanges.saveError"));
    } catch {
      if (mounted.current) setError(t("settings.unsavedChanges.saveError"));
    } finally {
      savingRef.current = false;
      if (mounted.current) setBusy(false);
    }
  }

  const confirmationDialog = open ? (
    <ErpConfirmDialog
      title={t("settings.unsavedChanges.title")}
      message={t("settings.unsavedChanges.message")}
      confirmLabel={t("common.save")}
      cancelLabel={t("common.cancel")}
      onConfirm={() => { void saveAndContinue(); }}
      onCancel={discardAndContinue}
      busy={busy || saving}
      errorMessage={error}
      initialFocus="confirm"
    />
  ) : null;

  return { requestNavigation, confirmationDialog };
}
