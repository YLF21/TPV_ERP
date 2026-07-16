import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiRequest } from "../api/client";
import type { LocaleCode } from "../types";

type PurchaseDocument = {
  id: string;
  tipo: "ALBARAN_COMPRA" | "FACTURA_COMPRA" | "RECTIFICATIVA_COMPRA";
  estado: string;
  numero?: string | null;
  numeroExterno?: string | null;
  fecha: string;
  proveedorNombre?: string | null;
  almacenNombre?: string | null;
  lineas?: number;
};

type GoodsCheckItem = {
  productId: string;
  code: string;
  name: string;
  expectedQuantity: number | string;
  registeredQuantity: number | string;
  missingQuantity: number | string;
  extraQuantity: number | string;
};

export type GoodsCheckView = {
  id: string;
  documentId: string;
  status: "ABIERTA" | "COMPLETA" | "CON_DIFERENCIAS";
  todos: GoodsCheckItem[];
  faltantes: GoodsCheckItem[];
  registrados: GoodsCheckItem[];
};

type PagedResult<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

type GoodsCheckPanelProps = {
  locale: LocaleCode;
  token?: string;
  t: (key: string) => string;
};

const PAGE_LIMIT = 500;

export function goodsCheckDocumentPath(documentId: string) {
  return `/goods-checks/documents/${encodeURIComponent(documentId)}/import`;
}

export function goodsCheckScanPath(checkId: string) {
  return `/goods-checks/${encodeURIComponent(checkId)}/scan`;
}

export function goodsCheckClosePath(checkId: string) {
  return `/goods-checks/${encodeURIComponent(checkId)}/close`;
}

export function goodsCheckDocumentIsAvailable(document: PurchaseDocument) {
  return Boolean(document.id && document.numero)
    && document.estado !== "BORRADOR"
    && document.estado !== "ANULADO"
    && ["ALBARAN_COMPRA", "FACTURA_COMPRA"].includes(document.tipo);
}

async function loadDocumentPages(path: string, token: string) {
  const values: PurchaseDocument[] = [];
  const cursors = new Set<string>();
  let cursor: string | null = null;
  do {
    const params = new URLSearchParams({ limit: String(PAGE_LIMIT) });
    if (cursor) params.set("cursor", cursor);
    const page = await apiRequest<PagedResult<PurchaseDocument>>(`${path}?${params.toString()}`, { token });
    values.push(...page.items);
    const nextCursor: string | null = page.nextCursor?.trim() || null;
    if (!page.hasMore || !nextCursor || cursors.has(nextCursor)) break;
    cursors.add(nextCursor);
    cursor = nextCursor;
  } while (cursor);
  return values;
}

export async function loadGoodsCheckDocuments(token: string) {
  const [invoices, deliveryNotes] = await Promise.all([
    loadDocumentPages("/document-reports/invoices", token),
    loadDocumentPages("/document-reports/delivery-notes", token)
  ]);
  return [...invoices, ...deliveryNotes]
    .filter(goodsCheckDocumentIsAvailable)
    .sort((left, right) => right.fecha.localeCompare(left.fecha) || (right.numero ?? "").localeCompare(left.numero ?? ""));
}

export function GoodsCheckPanel({ locale, token, t }: GoodsCheckPanelProps) {
  const [documents, setDocuments] = useState<PurchaseDocument[]>([]);
  const [selectedDocumentId, setSelectedDocumentId] = useState("");
  const [search, setSearch] = useState("");
  const [check, setCheck] = useState<GoodsCheckView | null>(null);
  const [code, setCode] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const codeRef = useRef<HTMLInputElement | null>(null);
  const numberFormatter = useMemo(() => new Intl.NumberFormat(
    locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES",
    { maximumFractionDigits: 3 }
  ), [locale]);
  const selectedDocument = documents.find((document) => document.id === selectedDocumentId) ?? null;
  const visibleDocuments = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    if (!query) return documents;
    return documents.filter((document) => [
      document.numero,
      document.numeroExterno,
      document.proveedorNombre,
      document.almacenNombre,
      document.fecha
    ].some((value) => String(value ?? "").toLocaleLowerCase().includes(query)));
  }, [documents, search]);

  useEffect(() => {
    let cancelled = false;
    if (!token) {
      setDocuments([]);
      return;
    }
    setLoading(true);
    void loadGoodsCheckDocuments(token)
      .then((values) => {
        if (cancelled) return;
        setDocuments(values);
        setSelectedDocumentId((current) => values.some((value) => value.id === current) ? current : values[0]?.id ?? "");
      })
      .catch((error) => {
        if (!cancelled) setStatus(error instanceof Error ? error.message : t("goodsCheck.loadError"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [locale, token]);

  async function importDocument(document: PurchaseDocument | null = selectedDocument) {
    if (!token || !document) return;
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckDocumentPath(document.id), {
        token,
        method: "POST"
      });
      setSelectedDocumentId(document.id);
      setCheck(value);
      setStatus(t("goodsCheck.imported"));
      window.setTimeout(() => codeRef.current?.focus(), 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.importError"));
    } finally {
      setBusy(false);
    }
  }

  async function registerProduct(event: FormEvent) {
    event.preventDefault();
    if (!token || !check || check.status !== "ABIERTA" || !code.trim()) return;
    const normalizedQuantity = Number(quantity.replace(",", "."));
    if (!Number.isFinite(normalizedQuantity) || normalizedQuantity === 0) {
      setStatus(t("goodsCheck.quantityError"));
      return;
    }
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckScanPath(check.id), {
        token,
        method: "POST",
        body: { code: code.trim(), quantity: normalizedQuantity }
      });
      setCheck(value);
      setCode("");
      setQuantity("1");
      setStatus(t("goodsCheck.registered"));
      window.setTimeout(() => codeRef.current?.focus(), 0);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.scanError"));
    } finally {
      setBusy(false);
    }
  }

  async function closeCheck() {
    if (!token || !check || check.status !== "ABIERTA") return;
    setBusy(true);
    setStatus("");
    try {
      const value = await apiRequest<GoodsCheckView>(goodsCheckClosePath(check.id), {
        token,
        method: "POST"
      });
      setCheck(value);
      setStatus(value.status === "COMPLETA" ? t("goodsCheck.complete") : t("goodsCheck.differences"));
    } catch (error) {
      setStatus(error instanceof Error ? error.message : t("goodsCheck.closeError"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="goods-check-panel">
      <div className="goods-check-documents">
        <div className="stock-history-toolbar goods-check-toolbar">
          <label className="report-search">
            <span className="sr-only">{t("salesReport.search")}</span>
            <input
              type="search"
              value={search}
              placeholder={t("goodsCheck.searchPlaceholder")}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <button type="button" disabled={!selectedDocument || busy} onClick={() => void importDocument()}>
            {busy ? t("common.loading") : t("goodsCheck.import")}
          </button>
        </div>
        <div className="stock-history-context">
          <strong>{t("goodsCheck.purchaseDocuments")}</strong>
          <span>{visibleDocuments.length}</span>
        </div>
        <div className="stock-history-table-scroll goods-check-document-list">
          <table className="report-table">
            <thead>
              <tr>
                <th>{t("goodsCheck.column.type")}</th>
                <th>{t("goodsCheck.column.number")}</th>
                <th>{t("salesReport.column.date")}</th>
                <th>{t("warehouseDocument.supplier")}</th>
                <th>{t("stock.column.warehouse")}</th>
              </tr>
            </thead>
            <tbody>
              {visibleDocuments.map((document) => (
                <tr
                  key={document.id}
                  className={selectedDocumentId === document.id ? "selected" : ""}
                  tabIndex={0}
                  onClick={() => setSelectedDocumentId(document.id)}
                  onDoubleClick={() => {
                    void importDocument(document);
                  }}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      setSelectedDocumentId(document.id);
                    }
                  }}
                >
                  <td>{t(document.tipo === "ALBARAN_COMPRA" ? "goodsCheck.deliveryNote" : "goodsCheck.invoice")}</td>
                  <td>{document.numero}</td>
                  <td>{document.fecha}</td>
                  <td>{document.proveedorNombre || "-"}</td>
                  <td>{document.almacenNombre || "-"}</td>
                </tr>
              ))}
              {!loading && visibleDocuments.length === 0 && (
                <tr><td colSpan={5}>{t("goodsCheck.noDocuments")}</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="goods-check-workspace">
        <div className="stock-history-context goods-check-active-heading">
          <strong>{check ? `${t("goodsCheck.active")}: ${selectedDocument?.numero ?? ""}` : t("goodsCheck.noActive")}</strong>
          {check && <span className={`goods-check-status status-${check.status.toLocaleLowerCase()}`}>{t(`goodsCheck.status.${check.status}`)}</span>}
        </div>
        {check ? (
          <>
            <form className="goods-check-scan-form" onSubmit={registerProduct}>
              <label>
                <span>{t("goodsCheck.productCode")}</span>
                <input ref={codeRef} value={code} disabled={check.status !== "ABIERTA" || busy} onChange={(event) => setCode(event.target.value)} />
              </label>
              <label>
                <span>{t("goodsCheck.quantity")}</span>
                <input inputMode="decimal" value={quantity} disabled={check.status !== "ABIERTA" || busy} onChange={(event) => setQuantity(event.target.value)} />
              </label>
              <button type="submit" disabled={!code.trim() || check.status !== "ABIERTA" || busy}>{t("goodsCheck.register")}</button>
              <button type="button" className="secondary" disabled={check.status !== "ABIERTA" || busy} onClick={() => void closeCheck()}>{t("goodsCheck.close")}</button>
            </form>
            <div className="stock-history-table-scroll goods-check-lines">
              <table className="report-table">
                <thead>
                  <tr>
                    <th>{t("goodsCheck.column.code")}</th>
                    <th>{t("goodsCheck.column.product")}</th>
                    <th>{t("goodsCheck.column.expected")}</th>
                    <th>{t("goodsCheck.column.registered")}</th>
                    <th>{t("goodsCheck.column.missing")}</th>
                    <th>{t("goodsCheck.column.extra")}</th>
                  </tr>
                </thead>
                <tbody>
                  {check.todos.map((item) => (
                    <tr key={item.productId} className={Number(item.missingQuantity) > 0 || Number(item.extraQuantity) > 0 ? "goods-check-difference" : ""}>
                      <td>{item.code}</td>
                      <td>{item.name}</td>
                      <td>{numberFormatter.format(Number(item.expectedQuantity))}</td>
                      <td>{numberFormatter.format(Number(item.registeredQuantity))}</td>
                      <td>{numberFormatter.format(Number(item.missingQuantity))}</td>
                      <td>{numberFormatter.format(Number(item.extraQuantity))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : (
          <div className="stock-empty-state">{t("goodsCheck.selectDocument")}</div>
        )}
        {status && <p className="stock-operation-status" aria-live="polite">{status}</p>}
      </div>
    </section>
  );
}

export default GoodsCheckPanel;
