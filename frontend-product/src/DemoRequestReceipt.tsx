import { useEffect, useRef, useState } from "react";
import type { AppId } from "./applicationMetadata";
import type { DemoReceipt } from "./demoRequests";
import type { MarketingLanguage } from "./marketingI18n";
import { demoReceiptDetails, formatDemoReceiptSummary, getDemoReceiptContent } from "./demoReceiptContent";

export function DemoRequestReceipt({ receipt, applications, language }: {
  receipt: DemoReceipt;
  applications: readonly AppId[];
  language: MarketingLanguage;
}) {
  const copy = getDemoReceiptContent(language);
  const details = demoReceiptDetails(receipt, applications, language);
  const summary = formatDemoReceiptSummary(receipt, applications, language);
  const [state, setState] = useState<"idle" | "copying" | "copied" | "error">("idle");
  const attempt = useRef(0);
  const fallback = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    attempt.current += 1;
    setState("idle");
    return () => { attempt.current += 1; };
  }, [summary]);

  useEffect(() => {
    if (state === "error") { fallback.current?.focus(); fallback.current?.select(); }
  }, [state]);

  async function copySummary() {
    const current = ++attempt.current;
    setState("copying");
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(summary);
      if (current === attempt.current) setState("copied");
    } catch {
      if (current === attempt.current) setState("error");
    }
  }

  return <section className="mk-demo-receipt" data-testid="demo-receipt" aria-label={copy.title}>
    <span>{copy.reference}:</span><code>{receipt.id}</code>
    <p>{copy.received}: <time dateTime={receipt.receivedAt}>{details.date}</time></p>
    <p>{copy.apps}: {details.apps}</p>
    <button className="mk-service-action" type="button" onClick={copySummary} disabled={state === "copying"} aria-busy={state === "copying"}>{copy.copy}</button>
    <p role="status" aria-live="polite" aria-atomic="true">{state === "copied" ? copy.copied : state === "error" ? copy.manual : ""}</p>
    {state === "error" && <label>{copy.summary}<textarea ref={fallback} readOnly rows={3} value={summary} spellCheck={false} onFocus={(event) => event.currentTarget.select()} /></label>}
  </section>;
}
