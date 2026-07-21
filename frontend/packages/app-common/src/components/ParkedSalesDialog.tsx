import { useEffect, useState } from "react";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

export type ParkedSaleSummary = {
  id: string;
  createdAt: string;
  customerId?: string | null;
  comment?: string | null;
  total: number | string;
};

export type OpenedParkedSale = {
  document: {
    clienteId?: string | null;
    lineas: Array<{
      productoId: string;
      cantidad: number | string;
      descuento: number | string;
    }>;
  };
  comment?: string | null;
};

type Props = {
  token?: string;
  locale: LocaleCode;
  currentSale: unknown;
  canPark: boolean;
  onClose: () => void;
  onParked: () => void;
  onRecovered: (sale: OpenedParkedSale) => void | Promise<void>;
};

export function ParkedSalesDialog({ token, locale, currentSale, canPark, onClose, onParked, onRecovered }: Props) {
  const t = createTranslator(locale);
  const [sales, setSales] = useState<ParkedSaleSummary[]>([]);
  const [comment, setComment] = useState("");
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState("");
  const [error, setError] = useState("");

  async function load() {
    setLoading(true);
    setError("");
    try {
      setSales(await apiRequest<ParkedSaleSummary[]>("/parked-sales", { token }));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.load"));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [token]);

  async function parkCurrent() {
    if (!canPark || busyId) return;
    setBusyId("new");
    setError("");
    try {
      await apiRequest("/parked-sales/from-pos", {
        token,
        method: "POST",
        body: { sale: currentSale, comment: comment.trim() || null }
      });
      setComment("");
      onParked();
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.park"));
    } finally {
      setBusyId("");
    }
  }

  async function recover(id: string) {
    if (busyId) return;
    setBusyId(id);
    setError("");
    try {
      const opened = await apiRequest<OpenedParkedSale>(`/parked-sales/${encodeURIComponent(id)}/open`, {
        token,
        method: "POST"
      });
      await onRecovered(opened);
      await apiRequest(`/parked-sales/${encodeURIComponent(id)}`, { token, method: "DELETE" });
      setSales((current) => current.filter((sale) => sale.id !== id));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.recover"));
    } finally {
      setBusyId("");
    }
  }

  async function remove(id: string) {
    if (busyId) return;
    setBusyId(id);
    setError("");
    try {
      await apiRequest(`/parked-sales/${encodeURIComponent(id)}`, { token, method: "DELETE" });
      setSales((current) => current.filter((sale) => sale.id !== id));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("parkedSales.error.delete"));
    } finally {
      setBusyId("");
    }
  }

  return (
    <div className="sale-action-overlay" role="presentation">
      <section className="sale-action-dialog wide parked-sales-dialog" role="dialog" aria-modal="true" aria-labelledby="parked-sales-title">
        <header><h2 id="parked-sales-title">{t("parkedSales.title")}</h2><button type="button" aria-label={t("common.close")} onClick={onClose}>×</button></header>
        <div className="parked-sale-create">
          <label><span>{t("parkedSales.comment")}</span><input autoFocus value={comment} onChange={(event) => setComment(event.target.value)} placeholder={t("parkedSales.commentPlaceholder")} /></label>
          <button type="button" className="primary" disabled={!canPark || Boolean(busyId)} onClick={() => void parkCurrent()}>
            {t("parkedSales.parkCurrent")}
          </button>
        </div>
        {error && <p className="sale-action-error" role="alert">{error}</p>}
        <div className="parked-sales-list">
          {loading && <p>{t("parkedSales.loading")}</p>}
          {!loading && sales.length === 0 && <p>{t("parkedSales.empty")}</p>}
          {sales.map((sale) => (
            <article key={sale.id}>
              <div><strong>{sale.comment?.trim() || t("parkedSales.untitled")}</strong><span>{new Date(sale.createdAt).toLocaleString(locale)}</span></div>
              <b>{new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(Number(sale.total))}</b>
              <button type="button" disabled={Boolean(busyId)} onClick={() => void recover(sale.id)}>{t("parkedSales.recover")}</button>
              <button type="button" className="danger" disabled={Boolean(busyId)} onClick={() => void remove(sale.id)}>{t("parkedSales.delete")}</button>
            </article>
          ))}
        </div>
        <footer><button type="button" onClick={onClose}>{t("common.close")}</button></footer>
      </section>
    </div>
  );
}
