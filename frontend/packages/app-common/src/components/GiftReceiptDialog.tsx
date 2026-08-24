import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { getHardwareBridge } from "../hardware/hardware";
import type { LocaleCode, TerminalContext } from "../types";
import {
  canonicalProductQuantity,
  formatProductQuantity,
  isProductQuantityPrecisionValid,
  productQuantityStep,
} from "../sale/productQuantity";

type PreviewLine = {
  lineId: string;
  code: string;
  name: string;
  productType: "UNIT" | "WEIGHT" | "SERVICE";
  availableQuantity: number | string;
  serialNumbers: string[];
};

type Preview = {
  ticketId: string;
  ticketNumber: string;
  issuedAt: string;
  lines: PreviewLine[];
};

type IssuedGiftReceipt = {
  id: string;
  code: string;
  issuedAt: string;
  sourceTicketId: string;
  sourceTicketNumber: string;
  lines: Array<{
    giftReceiptLineId: string;
    sourceLineId: string;
    code: string;
    name: string;
    quantity: number | string;
    serialNumbers: string[];
  }>;
};

type RenderedGiftReceipt = {
  renderedPdf?: { contentType: "application/pdf"; base64: string };
  ticketRenderedImage?: { contentType: "image/png"; base64: string };
  fileName?: string;
};

type Selection = { quantity: string; serialNumbers: string[] };

type Props = {
  token?: string;
  locale: LocaleCode;
  terminalContext: TerminalContext;
  onClose: () => void;
};

const copy = {
  es: {
    title: "Imprimir ticket regalo",
    ticket: "N.º de ticket",
    date: "Fecha",
    selection: "Selección",
    selectedProducts: "productos",
    selectedUnits: "unidades",
    selectLine: "Seleccionar producto",
    receiptCode: "Código generado",
    search: "Buscar ticket",
    selectAll: "Seleccionar todo el ticket",
    clear: "Quitar selección",
    code: "Código",
    product: "Producto",
    available: "Disponible",
    quantity: "Cantidad",
    serials: "Números de serie",
    issue: "Generar e imprimir",
    close: "Cerrar",
    loading: "Consultando ticket…",
    empty: "El ticket no tiene artículos disponibles.",
    selectionRequired: "Selecciona al menos un artículo.",
    ticketNotFound: "Ticket no encontrado",
    searchError: "No se pudo consultar el ticket.",
    issueError: "No se pudo generar el ticket regalo.",
    printError: "El ticket regalo se generó, pero no pudo imprimirse.",
    printed: "Ticket regalo impreso",
    retry: "Reintentar impresión",
  },
  en: {
    title: "Print gift receipt",
    ticket: "Ticket number",
    date: "Date",
    selection: "Selection",
    selectedProducts: "products",
    selectedUnits: "units",
    selectLine: "Select product",
    receiptCode: "Generated code",
    search: "Find ticket",
    selectAll: "Select entire ticket",
    clear: "Clear selection",
    code: "Code",
    product: "Product",
    available: "Available",
    quantity: "Quantity",
    serials: "Serial numbers",
    issue: "Create and print",
    close: "Close",
    loading: "Loading ticket…",
    empty: "The ticket has no available items.",
    selectionRequired: "Select at least one item.",
    ticketNotFound: "Ticket not found",
    searchError: "The ticket could not be loaded.",
    issueError: "The gift receipt could not be created.",
    printError: "The gift receipt was created but could not be printed.",
    printed: "Gift receipt printed",
    retry: "Retry printing",
  },
  zh: {
    title: "打印礼品小票",
    ticket: "小票编号",
    date: "日期",
    selection: "已选",
    selectedProducts: "件商品",
    selectedUnits: "个单位",
    selectLine: "选择商品",
    receiptCode: "已生成编码",
    search: "查找小票",
    selectAll: "选择整张小票",
    clear: "清除选择",
    code: "编码",
    product: "商品",
    available: "可用",
    quantity: "数量",
    serials: "序列号",
    issue: "生成并打印",
    close: "关闭",
    loading: "正在读取小票…",
    empty: "该小票没有可用商品。",
    selectionRequired: "请至少选择一个商品。",
    ticketNotFound: "未找到小票",
    searchError: "无法读取小票。",
    issueError: "无法生成礼品小票。",
    printError: "礼品小票已生成，但打印失败。",
    printed: "礼品小票已打印",
    retry: "重试打印",
  },
} as const;

function requestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

function apiMessage(error: unknown, fallback: string) {
  if (!(error instanceof ApiError)) return fallback;
  const detail = error.problem?.detail;
  return typeof detail === "string" && detail.trim() ? detail.trim() : fallback;
}

function isMissingTicketError(error: unknown) {
  if (!(error instanceof ApiError)) return false;
  const code = typeof error.problem?.code === "string" ? error.problem.code : "";
  return code === "TICKET_NOT_FOUND"
    || (error.status === 400 && code === "VALIDATION_ERROR");
}

function formatIssuedAt(value: string, locale: LocaleCode) {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale, {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(parsed);
}

export function GiftReceiptDialog({ token, locale, terminalContext, onClose }: Props) {
  const t = copy[locale];
  const inputRef = useRef<HTMLInputElement>(null);
  const issueRequestRef = useRef("");
  const ticketEditedRef = useRef(false);
  const [ticketNumber, setTicketNumber] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [selections, setSelections] = useState<Record<string, Selection>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [issuedCode, setIssuedCode] = useState("");

  useEffect(() => {
    let active = true;
    const focusAndSelectTicket = () => globalThis.setTimeout(() => {
      if (!active) return;
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 0);

    void apiRequest<{ numero?: string | null }>("/tickets/last-current-terminal", { token })
      .then((ticket) => {
        if (!active || ticketEditedRef.current || !ticket.numero?.trim()) return;
        setTicketNumber(ticket.numero.trim());
      })
      .catch(() => undefined)
      .finally(focusAndSelectTicket);

    return () => {
      active = false;
    };
  }, [token]);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || busy) return;
      event.preventDefault();
      onClose();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [busy, onClose]);

  const selectedLines = useMemo(() => preview?.lines.flatMap((line) => {
    const selection = selections[line.lineId];
    const quantity = Number(selection?.quantity ?? 0);
    return Number.isFinite(quantity) && quantity > 0
        && quantity <= Number(line.availableQuantity)
        && isProductQuantityPrecisionValid(quantity, line.productType)
      ? [{ lineId: line.lineId, quantity, serialNumbers: selection?.serialNumbers ?? [] }]
      : [];
  }) ?? [], [preview, selections]);
  const selectionIsValid = selectedLines.length > 0
    && selectedLines.length === Object.keys(selections).length;
  const selectedQuantity = selectedLines.reduce((total, line) => total + line.quantity, 0);

  async function search() {
    const normalized = ticketNumber.trim();
    if (!normalized || busy) return;
    setBusy(true); setError(""); setMessage(""); setIssuedCode("");
    try {
      const result = await apiRequest<Preview>(
        `/gift-receipts/preview?ticketNumber=${encodeURIComponent(normalized)}`,
        { token },
      );
      setPreview(result);
      setTicketNumber(result.ticketNumber);
      setSelections({});
      issueRequestRef.current = "";
    } catch (nextError) {
      setPreview(null);
      setSelections({});
      setError(isMissingTicketError(nextError)
        ? t.ticketNotFound
        : apiMessage(nextError, t.searchError));
    } finally {
      setBusy(false);
    }
  }

  function selectAll() {
    if (!preview) return;
    issueRequestRef.current = "";
    setSelections(Object.fromEntries(preview.lines.map((line) => [
      line.lineId,
      {
        quantity: canonicalProductQuantity(line.availableQuantity),
        serialNumbers: [...(line.serialNumbers ?? [])],
      },
    ])));
    setError("");
  }

  function toggleLine(line: PreviewLine, selected: boolean) {
    issueRequestRef.current = "";
    setSelections((current) => {
      const next = { ...current };
      if (!selected) delete next[line.lineId];
      else next[line.lineId] = {
        quantity: line.serialNumbers?.length ? "1" : "1",
        serialNumbers: line.serialNumbers?.length ? [line.serialNumbers[0]] : [],
      };
      return next;
    });
  }

  function updateQuantity(line: PreviewLine, value: string) {
    issueRequestRef.current = "";
    setSelections((current) => ({
      ...current,
      [line.lineId]: {
        quantity: value,
        serialNumbers: line.serialNumbers?.length
          ? line.serialNumbers.slice(0, Math.max(0, Math.trunc(Number(value) || 0)))
          : [],
      },
    }));
  }

  function toggleSerial(line: PreviewLine, serial: string, selected: boolean) {
    issueRequestRef.current = "";
    setSelections((current) => {
      const prior = current[line.lineId] ?? { quantity: "0", serialNumbers: [] };
      const serialNumbers = selected
        ? [...prior.serialNumbers, serial]
        : prior.serialNumbers.filter((value) => value !== serial);
      return {
        ...current,
        [line.lineId]: { quantity: String(serialNumbers.length), serialNumbers },
      };
    });
  }

  async function printIssuedDocument(code: string, issued?: IssuedGiftReceipt) {
    const rendered = await apiRequest<RenderedGiftReceipt>(
      `/gift-receipts/${encodeURIComponent(code)}/print-document`,
      { token },
    );
    if (!rendered.renderedPdf || !rendered.ticketRenderedImage) {
      throw new Error("gift_receipt_rendered_document_missing");
    }
    const hardware = getHardwareBridge();
    const config = await hardware.getHardwareConfig();
    const result = await hardware.printTicket({
      requireRenderedDocument: true,
      layout: "GIFT_RECEIPT",
      documentNumber: code,
      storeName: terminalContext.storeName,
      terminalCode: terminalContext.terminalCode,
      issuedAt: issued?.issuedAt ?? new Date().toISOString(),
      lines: (issued?.lines ?? []).map((line) => ({
        code: line.code,
        name: line.name,
        quantity: Number(line.quantity),
        price: 0,
        total: 0,
        serialNumbers: line.serialNumbers,
      })),
      payments: [],
      total: 0,
      renderedPdf: rendered.renderedPdf,
      documentRaster: `data:${rendered.ticketRenderedImage.contentType};base64,${rendered.ticketRenderedImage.base64}`,
      labels: {
        terminal: locale === "en" ? "Terminal" : locale === "zh" ? "终端" : "Terminal",
        item: t.product,
        quantity: t.quantity,
        price: "",
        total: "",
      },
    }, config);
    if (!result.ok) throw new Error(result.message || "gift_receipt_print_failed");
  }

  async function issueAndPrint() {
    if (!preview || busy) return;
    if (!selectionIsValid) {
      setError(t.selectionRequired);
      return;
    }
    setBusy(true); setError(""); setMessage("");
    try {
      if (!issueRequestRef.current) issueRequestRef.current = requestId();
      const issued = await apiRequest<IssuedGiftReceipt>("/gift-receipts", {
        token,
        method: "POST",
        body: {
          requestId: issueRequestRef.current,
          ticketNumber: preview.ticketNumber,
          lines: selectedLines,
        },
      });
      setIssuedCode(issued.code);
      await printIssuedDocument(issued.code, issued);
      setMessage(`${t.printed}: ${issued.code}`);
    } catch (nextError) {
      setError(apiMessage(nextError, t.issueError));
    } finally {
      setBusy(false);
    }
  }

  async function retryPrint() {
    if (!issuedCode || busy) return;
    setBusy(true); setError(""); setMessage("");
    try {
      await printIssuedDocument(issuedCode);
      setMessage(`${t.printed}: ${issuedCode}`);
    } catch (nextError) {
      setError(apiMessage(nextError, t.printError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section className="gift-receipt-dialog" role="dialog" aria-modal="true" aria-labelledby="gift-receipt-title" aria-busy={busy}>
        <header>
          <h2 id="gift-receipt-title">{t.title}</h2>
          <button type="button" className="sale-dialog-close" aria-label={t.close} disabled={busy} onClick={onClose}>×</button>
        </header>
        <form className="gift-receipt-search" onSubmit={(event) => { event.preventDefault(); void search(); }}>
          <label><span>{t.ticket}</span><input ref={inputRef} disabled={busy} value={ticketNumber} onChange={(event) => {
            ticketEditedRef.current = true;
            setTicketNumber(event.currentTarget.value);
          }} /></label>
          <button type="submit" className="gift-receipt-action gift-receipt-action-primary" disabled={busy || !ticketNumber.trim()}>{busy ? t.loading : t.search}</button>
        </form>
        {preview && <>
          <div className="gift-receipt-summary">
            <div><span>{t.ticket}</span><strong>{preview.ticketNumber}</strong></div>
            <div><span>{t.date}</span><strong>{formatIssuedAt(preview.issuedAt, locale)}</strong></div>
            <div className="selected"><span>{t.selection}</span><strong>{selectedLines.length} {t.selectedProducts} · {formatProductQuantity(selectedQuantity, "SERVICE", locale)} {t.selectedUnits}</strong></div>
          </div>
          <div className="gift-receipt-toolbar">
            <strong>{preview.lines.length} {t.selectedProducts}</strong>
            <div>
              <button type="button" className="gift-receipt-action gift-receipt-action-secondary" disabled={busy || preview.lines.length === 0} onClick={selectAll}>{t.selectAll}</button>
              <button type="button" className="gift-receipt-action gift-receipt-action-neutral" disabled={busy || selectedLines.length === 0} onClick={() => { issueRequestRef.current = ""; setSelections({}); }}>{t.clear}</button>
            </div>
          </div>
          {preview.lines.length === 0 ? <p>{t.empty}</p> : <div className="gift-receipt-lines">
            <table>
              <thead><tr><th aria-label="Seleccionar" /><th>{t.code}</th><th>{t.product}</th><th>{t.available}</th><th>{t.quantity}</th></tr></thead>
              <tbody>{preview.lines.map((line) => {
                const selection = selections[line.lineId];
                return <Fragment key={line.lineId}>
                  <tr className={selection ? "selected" : ""}>
                    <td><input type="checkbox" aria-label={`${t.selectLine}: ${line.name}`} disabled={busy} checked={Boolean(selection)} onChange={(event) => toggleLine(line, event.currentTarget.checked)} /></td>
                    <td>{line.code}</td><td>{line.name}</td><td>{formatProductQuantity(line.availableQuantity, line.productType, locale)}</td>
                    <td><input type="number" min={productQuantityStep(line.productType)} max={Number(line.availableQuantity)} step={productQuantityStep(line.productType)} disabled={busy || !selection || line.serialNumbers.length > 0} value={selection?.quantity ?? ""} onChange={(event) => updateQuantity(line, event.currentTarget.value)} /></td>
                  </tr>
                  {selection && line.serialNumbers.length > 0 && <tr className="gift-receipt-serial-row"><td className="gift-receipt-serials" colSpan={5}>
                    <strong>{t.serials}</strong>
                    {line.serialNumbers.map((serial) => <label key={serial}><input type="checkbox" disabled={busy} checked={selection.serialNumbers.includes(serial)} onChange={(event) => toggleSerial(line, serial, event.currentTarget.checked)} />{serial}</label>)}
                  </td></tr>}
                </Fragment>;
              })}</tbody>
            </table>
          </div>}
        </>}
        {(error || message) && <div className="gift-receipt-feedback">
          {error && <p className="sale-action-error" role="alert">{error}</p>}
          {message && <p className="sale-action-success" role="status">{message}</p>}
        </div>}
        <footer>
          {issuedCode && <div className="gift-receipt-issued"><span>{t.receiptCode}</span><strong>{issuedCode}</strong></div>}
          {issuedCode && error && <button type="button" className="gift-receipt-action gift-receipt-action-secondary" disabled={busy} onClick={() => void retryPrint()}>{t.retry}</button>}
          <button type="button" className="gift-receipt-action gift-receipt-action-secondary" disabled={busy} onClick={onClose}>{t.close}</button>
          <button type="button" className="gift-receipt-action gift-receipt-action-primary gift-receipt-action-issue" disabled={busy || !selectionIsValid} onClick={() => void issueAndPrint()}>{t.issue}</button>
        </footer>
      </section>
    </div>
  );
}
