import { useEffect, useId, useRef, useState } from "react";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpSelect } from "./ErpSelect";

type WindowMode = "FULLSCREEN" | "WINDOWED";

export function DesktopDisplaySettings({ locale }: { locale: LocaleCode }) {
  const t = createTranslator(locale);
  const id = useId();
  const display = typeof window === "undefined" ? undefined : window.tpvDesktop?.display;
  const [mode, setMode] = useState<WindowMode | null>(null);
  const [loading, setLoading] = useState(Boolean(display));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<"load" | "save" | null>(null);
  const mounted = useRef(false);
  const changing = useRef(false);

  useEffect(() => {
    let active = true;
    mounted.current = true;
    if (display) {
      setLoading(true);
      void display.load()
        .then((result) => {
          if (!active) return;
          if (!result.ok) throw new Error(result.code);
          setMode(result.mode);
        })
        .catch(() => { if (active) setError("load"); })
        .finally(() => { if (active) setLoading(false); });
    }
    return () => { active = false; mounted.current = false; };
  }, [display]);

  async function changeMode(value: string) {
    if (!display || !mode || changing.current || (value !== "FULLSCREEN" && value !== "WINDOWED")) return;
    changing.current = true;
    setSaving(true);
    setError(null);
    try {
      const result = await display.setMode(value);
      if (!result.ok) throw new Error(result.code);
      if (mounted.current) setMode(result.mode);
    } catch {
      try {
        const current = await display.load();
        if (mounted.current && current.ok) setMode(current.mode);
      } catch { /* Keep the last known mode if the window is unavailable. */ }
      if (mounted.current) setError("save");
    } finally {
      changing.current = false;
      if (mounted.current) setSaving(false);
    }
  }

  return (
    <section className="sale-settings-panel settings-window-display">
      <h3>{t("settings.window.title")}</h3>
      <p>{t("settings.window.description")}</p>
      <div className="sale-settings-window-control">
        <label htmlFor={id}>{t("settings.window.mode")}</label>
        <ErpSelect
          id={id}
          aria-label={t("settings.window.mode")}
          value={mode ?? ""}
          placeholder={loading ? t("settings.window.loading") : "—"}
          disabled={!display || !mode || loading || saving}
          options={[
            { value: "FULLSCREEN", label: t("settings.window.fullscreen") },
            { value: "WINDOWED", label: t("settings.window.windowed") }
          ]}
          onChange={(value) => { void changeMode(value); }}
        />
      </div>
      <p className="settings-report-note">
        {t(display ? "settings.window.localScope" : "settings.window.desktopOnly")}
      </p>
      {saving ? <p role="status">{t("settings.window.saving")}</p> : null}
      {error ? <p className="hardware-diagnostic-error" role="alert">{t(`settings.window.${error}Error`)}</p> : null}
    </section>
  );
}
