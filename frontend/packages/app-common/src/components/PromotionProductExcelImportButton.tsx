import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { readProductExcelImport, type ProductExcelImportReadResult } from "../api/productExcelImports";
import type { LocaleCode } from "../types";
import { excelCellText, excelColumnIndexToLetter, excelImportAccept, excelImportLimits } from "./excelImport";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";
import { WindowCloseButton } from "./WindowCloseButton";
import {
  classifyPromotionExcelRows,
  detectPromotionExcelMapping,
  promotionExcelColumns,
  type PromotionExcelCatalogProduct,
  type PromotionExcelMapping
} from "./PromotionProductExcelImport";
import { promotionExcelMessage } from "./PromotionProductExcelImport.messages";
import "../styles/shared-excel-import.css";
import "./PromotionProductExcelImport.css";

export type PromotionProductExcelImportButtonProps = {
  products: readonly PromotionExcelCatalogProduct[];
  selectedProductIds: readonly string[];
  onAddProducts: (ids: string[]) => void;
  locale: LocaleCode;
  token?: string;
};

const previewPageSize = 100;

export function PromotionProductExcelImportButton({
  products,
  selectedProductIds,
  onAddProducts,
  locale,
  token
}: PromotionProductExcelImportButtonProps) {
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [read, setRead] = useState<ProductExcelImportReadResult | null>(null);
  const [mapping, setMapping] = useState<PromotionExcelMapping>({ code: 0, barcode: -1, startRow: 1 });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [activePanel, setActivePanel] = useState<"mapping" | "results">("mapping");
  const [rawPage, setRawPage] = useState(0);
  const [resultPage, setResultPage] = useState(0);
  const dialogRef = useRef<HTMLElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const requestRef = useRef<AbortController | null>(null);
  const t = (key: Parameters<typeof promotionExcelMessage>[1], values?: Record<string, string | number>) =>
    promotionExcelMessage(locale, key, values);
  const rows = useMemo(
    () => (read ? classifyPromotionExcelRows(read, mapping, products, selectedProductIds) : []),
    [read, mapping, products, selectedProductIds]
  );
  const readyIds = rows.filter((row) => row.status === "ready" && row.product).map((row) => row.product!.id);
  const issueCount = rows.filter((row) =>
    ["missing", "ambiguous", "identifierMismatch", "inactive", "identifierRequired", "invalidCell"].includes(
      row.status
    )
  ).length;
  const skippedCount = rows.length - readyIds.length - issueCount;
  const columns = read ? promotionExcelColumns(read) : [];
  const rawPageCount = Math.max(1, Math.ceil((read?.rows.length ?? 0) / previewPageSize));
  const resultPageCount = Math.max(1, Math.ceil(rows.length / previewPageSize));

  useEffect(() => {
    if (!open || !dialogRef.current) return;
    return activateModalFocusTrap(dialogRef.current as ModalFocusRoot, document);
  }, [open]);
  useEffect(() => () => requestRef.current?.abort(), []);

  function close() {
    requestRef.current?.abort();
    requestRef.current = null;
    setLoading(false);
    setOpen(false);
  }

  async function chooseFile(nextFile: File | undefined) {
    requestRef.current?.abort();
    requestRef.current = null;
    setFile(nextFile ?? null);
    setRead(null);
    setError("");
    setLoading(false);
    setActivePanel("mapping");
    setRawPage(0);
    setResultPage(0);
    if (!nextFile) return;
    if (!/\.xlsx?$/i.test(nextFile.name)) {
      setError(t("invalidFile"));
      return;
    }
    if (nextFile.size > excelImportLimits.maxFileBytes) {
      setError(t("fileTooLarge"));
      return;
    }
    const controller = new AbortController();
    requestRef.current = controller;
    setLoading(true);
    try {
      const result = await readProductExcelImport(nextFile, token, controller.signal);
      if (controller.signal.aborted) return;
      setRead(result);
      setMapping(detectPromotionExcelMapping(result));
    } catch (cause) {
      if (controller.signal.aborted) return;
      setError(cause instanceof Error && cause.message ? `${t("readError")} ${cause.message}` : t("readError"));
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null;
        setLoading(false);
      }
    }
  }

  function addProducts() {
    if (mapping.code < 0 && mapping.barcode < 0) {
      setError(t("noColumn"));
      return;
    }
    if (readyIds.length === 0) return;
    onAddProducts(readyIds);
    close();
  }

  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        {t("button")}
      </button>
      {open &&
        createPortal(
          <div className="filter-overlay shared-excel-overlay promotion-excel-overlay" role="presentation">
            <section
              ref={dialogRef}
              className="filter-dialog shared-excel-dialog promotion-excel-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="promotion-excel-title"
              onKeyDown={(event) => {
                if (event.key !== "Escape") return;
                event.preventDefault();
                event.stopPropagation();
                close();
              }}
            >
              <header className="shared-excel-toolbar">
                <input
                  ref={fileInputRef}
                  type="file"
                  accept={excelImportAccept}
                  className="shared-excel-file-input"
                  aria-label={t("choose")}
                  onChange={(event) => void chooseFile(event.target.files?.[0])}
                />
                <div className="shared-excel-title">
                  <h2 id="promotion-excel-title">{t("title")}</h2>
                  <span>{file?.name ?? excelImportAccept}</span>
                </div>
                <div className="shared-excel-toolbar-actions">
                  <button
                    type="button"
                    onClick={() => {
                      if (fileInputRef.current) {
                        fileInputRef.current.value = "";
                        fileInputRef.current.click();
                      }
                    }}
                  >
                    {t("choose")}
                  </button>
                  <button
                    type="button"
                    disabled={!file}
                    onClick={() => {
                      requestRef.current?.abort();
                      requestRef.current = null;
                      setLoading(false);
                      setFile(null);
                      setRead(null);
                      setError("");
                      if (fileInputRef.current) fileInputRef.current.value = "";
                    }}
                  >
                    {t("clearFile")}
                  </button>
                </div>
                <WindowCloseButton aria-label={t("close")} onLight onClick={close}>
                  {t("close")}
                </WindowCloseButton>
              </header>
              <div className="shared-excel-top-pane">
                {read ? (
                  <div className="shared-excel-preview" tabIndex={0} aria-label={t("rawSheet")}>
                    <table>
                      <thead>
                        <tr>
                          <th>#</th>
                          {columns.map((column) => (
                            <th key={column.index}>{excelColumnIndexToLetter(column.index)}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {read.rows
                          .slice(rawPage * previewPageSize, (rawPage + 1) * previewPageSize)
                          .map((cells, index) => {
                            const rowIndex = rawPage * previewPageSize + index;
                            return (
                              <tr key={rowIndex}>
                                <th>{rowIndex + 1}</th>
                                {columns.map((column) => (
                                  <td
                                    key={column.index}
                                    className={
                                      cells[column.index]?.errorCode ? "shared-excel-cell-error" : undefined
                                    }
                                  >
                                    {excelCellText(cells[column.index]?.value)}
                                  </td>
                                ))}
                              </tr>
                            );
                          })}
                      </tbody>
                    </table>
                    {rawPageCount > 1 && (
                      <div className="promotion-excel-pagination">
                        <button type="button" disabled={rawPage === 0} onClick={() => setRawPage(rawPage - 1)}>
                          {t("previous")}
                        </button>
                        <span>{t("page", { current: rawPage + 1, total: rawPageCount })}</span>
                        <button
                          type="button"
                          disabled={rawPage + 1 >= rawPageCount}
                          onClick={() => setRawPage(rawPage + 1)}
                        >
                          {t("next")}
                        </button>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="shared-excel-empty-preview">
                    <h3>{t("mapping")}</h3>
                    <p>{t("fileHint")}</p>
                  </div>
                )}
              </div>
              <div className="shared-excel-body promotion-excel-body">
                {activePanel === "mapping" ? (
                  <div className="shared-excel-config">
                    <div className="shared-excel-config-bar">
                      <label>
                        <span>{t("startRow")}</span>
                        <input
                          type="number"
                          min={1}
                          max={Math.max(1, read?.rows.length ?? 1)}
                          disabled={!read}
                          value={mapping.startRow}
                          onChange={(event) =>
                            setMapping({
                              ...mapping,
                              startRow: Math.max(
                                1,
                                Math.min(read?.rows.length || 1, Number(event.target.value) || 1)
                              )
                            })
                          }
                        />
                      </label>
                      <span>
                        {t("summary", { ready: readyIds.length, issues: issueCount, skipped: skippedCount })}
                      </span>
                    </div>
                    <div className="shared-excel-mapping promotion-excel-mapping">
                      {(["code", "barcode"] as const).map((field) => (
                        <label key={field} className="shared-excel-mapping-row">
                          <span>{field === "code" ? "A" : "B"}</span>
                          <span>{t(field)}</span>
                          <select
                            disabled={!read}
                            value={mapping[field]}
                            onChange={(event) => setMapping({ ...mapping, [field]: Number(event.target.value) })}
                          >
                            <option value={-1}>{t("none")}</option>
                            {columns.map((column) => (
                              <option key={column.index} value={column.index}>
                                {column.label}
                              </option>
                            ))}
                          </select>
                        </label>
                      ))}
                      {read && <p className="shared-excel-mapping-hint">{t("fileHint")}</p>}
                    </div>
                    <div className="shared-excel-config-actions">
                      <button type="button" disabled={!read} onClick={() => setActivePanel("results")}>
                        {t("review")}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="shared-excel-results promotion-excel-results">
                    <table>
                      <thead>
                        <tr>
                          <th>{t("row")}</th>
                          <th>{t("code")}</th>
                          <th>{t("barcode")}</th>
                          <th>{t("product")}</th>
                          <th>{t("result")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rows.slice(resultPage * previewPageSize, (resultPage + 1) * previewPageSize).map((row) => (
                          <tr
                            key={row.rowNumber}
                            className={row.status === "ready" ? "" : "promotion-excel-row-issue"}
                          >
                            <td>{row.rowNumber}</td>
                            <td>{row.code}</td>
                            <td>{row.barcode}</td>
                            <td>
                              {row.product ? [row.product.code, row.product.name].filter(Boolean).join(" · ") : "—"}
                            </td>
                            <td>{t(row.status)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {rows.length === 0 && <p>{t("empty")}</p>}
                    {resultPageCount > 1 && (
                      <div className="promotion-excel-pagination">
                        <button
                          type="button"
                          disabled={resultPage === 0}
                          onClick={() => setResultPage(resultPage - 1)}
                        >
                          {t("previous")}
                        </button>
                        <span>{t("page", { current: resultPage + 1, total: resultPageCount })}</span>
                        <button
                          type="button"
                          disabled={resultPage + 1 >= resultPageCount}
                          onClick={() => setResultPage(resultPage + 1)}
                        >
                          {t("next")}
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
              {(loading || error) && (
                <p className="shared-excel-status" role={error ? "alert" : "status"}>
                  {error || t("loading")}
                </p>
              )}
              <nav className="shared-excel-bottom-tabs" aria-label={t("sections")}>
                <button
                  type="button"
                  className={activePanel === "mapping" ? "selected" : ""}
                  onClick={() => setActivePanel("mapping")}
                >
                  {t("mapping")}
                </button>
                <button
                  type="button"
                  className={activePanel === "results" ? "selected" : ""}
                  disabled={!read}
                  onClick={() => setActivePanel("results")}
                >
                  {t("review")} ({rows.length})
                </button>
                <button
                  type="button"
                  className="promotion-excel-add"
                  disabled={!read || loading || readyIds.length === 0 || (mapping.code < 0 && mapping.barcode < 0)}
                  onClick={addProducts}
                >
                  {t("add", { count: readyIds.length })}
                </button>
              </nav>
            </section>
          </div>,
          document.body
        )}
    </>
  );
}
