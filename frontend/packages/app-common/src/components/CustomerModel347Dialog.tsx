import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { apiRequest, classifyApiFailure } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import "./CustomerModel347Dialog.css";

type Props = {
  customer: { id: string; clientId: string; fiscalName: string };
  session: UserSession;
  locale: LocaleCode;
  onClose: () => void;
};

export function CustomerModel347Dialog({ customer, session, locale, onClose }: Props) {
  const t = createTranslator(locale);
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const [busy, setBusy] = useState(false);
  const [errorKey, setErrorKey] = useState("");
  const [saved, setSaved] = useState(false);
  const dialogRef = useRef<HTMLElement>(null);
  const yearRef = useRef<HTMLInputElement>(null);
  const requestRef = useRef<AbortController | null>(null);

  useLayoutEffect(() => {
    const root = dialogRef.current;
    if (!root) return;
    const deactivate = activateModalFocusTrap(root as unknown as ModalFocusRoot, document);
    yearRef.current?.focus();
    yearRef.current?.select();
    return deactivate;
  }, []);

  useEffect(() => () => { requestRef.current?.abort(); }, [customer.id, session.accessToken, locale]);

  useEffect(() => {
    function handleKey(event: KeyboardEvent) {
      if (event.key !== "Escape" || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
      event.preventDefault(); event.stopImmediatePropagation();
      if (!event.repeat) { requestRef.current?.abort(); onClose(); }
    }
    window.addEventListener("keydown", handleKey, true);
    return () => window.removeEventListener("keydown", handleKey, true);
  }, [onClose]);

  function close() { requestRef.current?.abort(); onClose(); }

  async function generate() {
    if (requestRef.current) return;
    const selectedYear = Number(year);
    if (!/^\d{1,4}$/.test(year) || selectedYear < 1 || selectedYear > 9998) {
      setErrorKey("customerModel347.invalidYear"); yearRef.current?.focus(); return;
    }
    const controller = new AbortController();
    requestRef.current = controller; setBusy(true); setErrorKey(""); setSaved(false);
    try {
      const query = new URLSearchParams({ year: String(selectedYear), locale });
      const blob = await apiRequest<Blob>(`/customer-document-reports/${encodeURIComponent(customer.id)}/model-347.pdf?${query}`, {
        token: session.accessToken, responseType: "blob", signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      const fileName = [customer.clientId, customer.fiscalName, "Modelo 347", String(selectedYear)]
        .map((part) => part.trim().replace(/[<>:"/\\|?*\u0000-\u001f]+/g, "-")
          .replace(/\s+/g, " ").slice(0, 80).replace(/[. ]+$/, ""))
        .join("-") + ".pdf";
      if (window.tpvDesktop?.reports) {
        const bytes = new Uint8Array(await blob.arrayBuffer());
        if (controller.signal.aborted) return;
        const result = await window.tpvDesktop.reports.saveFile({
          defaultFileName: fileName, filters: [{ name: "PDF", extensions: ["pdf"] }], bytes,
        });
        if (controller.signal.aborted) return;
        if (!result.ok) throw new Error("model347_save_failed");
        if (result.canceled) return;
      } else {
        const url = URL.createObjectURL(blob);
        try {
          const link = document.createElement("a");
          link.href = url; link.download = fileName; link.click();
        } finally { URL.revokeObjectURL(url); }
      }
      setSaved(true);
    } catch (failure: unknown) {
      if (!controller.signal.aborted) setErrorKey(classifyApiFailure(failure) === "forbidden"
        ? "customerDocuments.noAccess" : "customerModel347.error");
    } finally {
      if (requestRef.current === controller) requestRef.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return <div className="filter-overlay customer-model347-overlay">
    <section className="customer-model347-dialog" ref={dialogRef} role="dialog" aria-modal="true"
      aria-labelledby="customer-model347-title" aria-describedby="customer-model347-scope customer-model347-notice">
      <header><h2 id="customer-model347-title">{t("customerModel347.title")}</h2></header>
      <form noValidate onSubmit={(event) => { event.preventDefault(); void generate(); }}>
        <p className="customer-model347-customer">{customer.clientId} · {customer.fiscalName}</p>
        <label className="customer-model347-year"><span>{t("customerModel347.year")}</span>
          <input ref={yearRef} type="number" inputMode="numeric" min={1} max={9998} step={1} autoComplete="off"
            value={year} readOnly={busy} aria-invalid={errorKey === "customerModel347.invalidYear"}
            aria-describedby={errorKey === "customerModel347.invalidYear" ? "customer-model347-error" : undefined}
            onChange={(event) => { setYear(event.target.value); setErrorKey(""); setSaved(false); }} />
        </label>
        <p id="customer-model347-scope">{t("customerModel347.scope")}</p>
        <p id="customer-model347-notice" className="customer-model347-notice">{t("customerModel347.notice")}</p>
        {errorKey && <p id="customer-model347-error" className="customer-model347-error" role="alert">{t(errorKey)}</p>}
        {(busy || saved) && <p role="status">{t(busy ? "customerModel347.generating" : "customerModel347.saved")}</p>}
        <footer>
          <button type="button" onClick={close}>{t("customerDocuments.close")}</button>
          <button type="submit" className="customer-model347-generate" disabled={busy}>
            {t(busy ? "customerModel347.generating" : "customerModel347.generate")}
          </button>
        </footer>
      </form>
    </section>
  </div>;
}
