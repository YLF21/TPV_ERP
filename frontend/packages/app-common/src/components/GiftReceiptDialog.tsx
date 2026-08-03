import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, apiRequest } from "../api/client";
import { getHardwareBridge } from "../hardware/hardware";
import type { LocaleCode, TerminalContext } from "../types";

type PreviewLine = {
  lineId: string;
  code: string;
  name: string;
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
    description: "Busca el ticket, selecciona los artículos y genera un justificante sin precios.",
    ticket: "N.º de ticket",
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
    searchError: "No se pudo consultar el ticket.",
    issueError: "No se pudo generar el ticket regalo.",
    printError: "El ticket regalo se generó, pero no pudo imprimirse.",
    printed: "Ticket regalo impreso",
  },
  en: {
    title: "Print gift receipt",
    description: "Find the ticket, select the items and create a receipt without prices.",
    ticket: "Ticket number",
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
    searchError: "The ticket could not be loaded.",
    issueError: "The gift receipt could not be created.",
    printError: "The gift receipt was created but could not be printed.",
    printed: "Gift receipt printed",
  },
  zh: {
    title: "打印礼品小票",
    description: "查找小票，选择商品并生成不含价格的凭证。",
    ticket: "小票编号",
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
    searchError: "无法读取小票。",
    issueError: "无法生成礼品小票。",
    printError: "礼品小票已生成，但打印失败。",
    printed: "礼品小票已打印",
  },
} as const;

function requestId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
}

function apiMessage(error: unknown, fallback: string) {
  return error instanceof ApiError && error.message ? error.message : fallback;
}

export function GiftReceiptDialog({ token, locale, terminalContext, onClose }: Props) {
  const t = copy[locale];
  const inputRef = useRef<HTMLInputElement>(null);
  const issueRequestRef = useRef("");
  const [ticketNumber, setTicketNumber] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [selections, setSelections] = useState<Record<string, Selection>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [issuedCode, setIssuedCode] = useState("");

  useEffect(() => { inputRef.current?.focus(); }, []);

  const selectedLines = useMemo(() => preview?.lines.flatMap((line) => {
    const selection = selections[line.lineId];
    const quantity = Number(selection?.quantity ?? 0);
    return Number.isFinite(quantity) && quantity > 0
      ? [{ lineId: line.lineId, quantity, serialNumbers: selection?.serialNumbers ?? [] }]
      : [];
  }) ?? [], [preview, selections]);

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
      setError(apiMessage(nextError, t.searchError));
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
        quantity: String(Number(line.availableQuantity)),
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

  async function issueAndPrint() {
    if (!preview || busy) return;
    if (selectedLines.length === 0) {
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
      const hardware = getHardwareBridge();
      const config = await hardware.getHardwareConfig();
      const printed = await hardware.printTicket({
        layout: "GIFT_RECEIPT",
        documentNumber: issued.code,
        storeName: terminalContext.storeName,
        terminalCode: terminalContext.terminalCode,
        issuedAt: issued.issuedAt,
        lines: issued.lines.map((line) => ({
          code: line.code,
          name: line.name,
          quantity: Number(line.quantity),
          price: 0,
          total: 0,
          serialNumbers: line.serialNumbers,
        })),
        payments: [],
        total: 0,
        labels: {
          terminal: locale === "en" ? "Terminal" : locale === "zh" ? "终端" : "Terminal",
          item: t.product,
          quantity: t.quantity,
          price: "",
          total: "",
        },
      }, config);
      setMessage(printed.ok ? `${t.printed}: ${issued.code}` : t.printError);
      if (!printed.ok) setError(t.printError);
    } catch (nextError) {
      setError(apiMessage(nextError, t.issueError));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section className="gift-receipt-dialog" role="dialog" aria-modal="true" aria-labelledby="gift-receipt-title">
        <header>
          <div><h2 id="gift-receipt-title">{t.title}</h2><p>{t.description}</p></div>
          <button type="button" className="sale-dialog-close" aria-label={t.close} onClick={onClose}>×</button>
        </header>
        <form className="gift-receipt-search" onSubmit={(event) => { event.preventDefault(); void search(); }}>
          <label><span>{t.ticket}</span><input ref={inputRef} value={ticketNumber} onChange={(event) => setTicketNumber(event.currentTarget.value)} /></label>
          <button type="submit" disabled={busy || !ticketNumber.trim()}>{busy ? t.loading : t.search}</button>
        </form>
        {preview && <>
          <div className="gift-receipt-toolbar">
            <strong>{preview.ticketNumber}</strong>
            <div>
              <button type="button" onClick={selectAll}>{t.selectAll}</button>
              <button type="button" onClick={() => { issueRequestRef.current = ""; setSelections({}); }}>{t.clear}</button>
            </div>
          </div>
          {preview.lines.length === 0 ? <p>{t.empty}</p> : <div className="gift-receipt-lines">
            <table>
              <thead><tr><th aria-label="Seleccionar" /><th>{t.code}</th><th>{t.product}</th><th>{t.available}</th><th>{t.quantity}</th></tr></thead>
              <tbody>{preview.lines.map((line) => {
                const selection = selections[line.lineId];
                return <Fragment key={line.lineId}>
                  <tr>
                    <td><input type="checkbox" checked={Boolean(selection)} onChange={(event) => toggleLine(line, event.currentTarget.checked)} /></td>
                    <td>{line.code}</td><td>{line.name}</td><td>{String(line.availableQuantity)}</td>
                    <td><input type="number" min="0.001" max={Number(line.availableQuantity)} step="0.001" disabled={!selection || line.serialNumbers.length > 0} value={selection?.quantity ?? ""} onChange={(event) => updateQuantity(line, event.currentTarget.value)} /></td>
                  </tr>
                  {selection && line.serialNumbers.length > 0 && <tr><td className="gift-receipt-serials" colSpan={5}>
                    <strong>{t.serials}</strong>
                    {line.serialNumbers.map((serial) => <label key={serial}><input type="checkbox" checked={selection.serialNumbers.includes(serial)} onChange={(event) => toggleSerial(line, serial, event.currentTarget.checked)} />{serial}</label>)}
                  </td></tr>}
                </Fragment>;
              })}</tbody>
            </table>
          </div>}
        </>}
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        {message && <p className="sale-action-success" role="status">{message}</p>}
        <footer>
          {issuedCode && <strong>{issuedCode}</strong>}
          <button type="button" onClick={onClose}>{t.close}</button>
          <button type="button" className="primary" disabled={busy || selectedLines.length === 0} onClick={() => void issueAndPrint()}>{t.issue}</button>
        </footer>
      </section>
    </div>
  );
}
