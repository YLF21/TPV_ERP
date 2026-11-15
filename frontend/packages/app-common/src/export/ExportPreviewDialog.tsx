import { useCallback, useEffect, useRef, useState } from "react";
import type { PDFDocumentLoadingTask, PDFDocumentProxy, RenderTask } from "pdfjs-dist";
import { ArrowLeft, ArrowRight, ArrowsIn, DownloadSimple, MagnifyingGlassMinus, MagnifyingGlassPlus, Printer, X } from "@phosphor-icons/react";
import type { LocaleCode } from "../types";
import { readWorkbookPreview, workbookPrintHtml, workbookRowCells, workbookColumnCount, type PreviewSheet } from "./workbookPreview";
import { pdfPrintHtml } from "./pdfPrintHtml";
import type { ExportPreviewOutcome, ExportPreviewRequest } from "./previewExport";
import { activateModalFocusTrap, type ModalFocusRoot } from "../components/modalFocusTrap";
import "./ExportPreviewDialog.css";

type Labels = {
  title: string; loading: string; failed: string; tooLarge: string; close: string; download: string; print: string;
  first: string; previous: string; next: string; last: string; page: string; of: string; fit: string;
  zoomIn: string; zoomOut: string; sheet: string; saved: string; printed: string; canceled: string;
};
const labels: Record<LocaleCode, Labels> = {
  es: { title: "Vista previa de exportación", loading: "Cargando vista previa…", failed: "No se pudo mostrar la vista previa. Puede descargar el archivo original.", tooLarge: "El archivo es demasiado grande para mostrarlo. Puede descargar el original.", close: "Cerrar", download: "Descargar", print: "Imprimir", first: "Primera página", previous: "Página anterior", next: "Página siguiente", last: "Última página", page: "Página", of: "de", fit: "Ajustar", zoomIn: "Ampliar", zoomOut: "Reducir", sheet: "Hoja", saved: "Archivo descargado", printed: "Enviado a imprimir", canceled: "Guardado cancelado" },
  en: { title: "Export preview", loading: "Loading preview…", failed: "The preview could not be displayed. You can download the original file.", tooLarge: "The file is too large to preview. You can download the original.", close: "Close", download: "Download", print: "Print", first: "First page", previous: "Previous page", next: "Next page", last: "Last page", page: "Page", of: "of", fit: "Fit", zoomIn: "Zoom in", zoomOut: "Zoom out", sheet: "Sheet", saved: "File downloaded", printed: "Sent to print", canceled: "Save canceled" },
  zh: { title: "导出预览", loading: "正在加载预览…", failed: "无法显示预览。您仍可下载原始文件。", tooLarge: "文件过大，无法预览。您仍可下载原始文件。", close: "关闭", download: "下载", print: "打印", first: "第一页", previous: "上一页", next: "下一页", last: "最后一页", page: "页", of: "共", fit: "适合页面", zoomIn: "放大", zoomOut: "缩小", sheet: "工作表", saved: "文件已下载", printed: "已发送至打印机", canceled: "已取消保存" }
};

type Props = ExportPreviewRequest & { onClose: (result: ExportPreviewOutcome) => void };
type DesktopExportPrint = { printExport: (request: { html: string }) => Promise<{ ok: boolean; canceled?: boolean; message?: string }> };
const WORKBOOK_ROW_BATCH = 200;
const WORKBOOK_ROW_NUMBER_WIDTH = 56;

function browserPrint(urlOrHtml: string, isHtml: boolean, onCleanup?: () => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const frame = document.createElement("iframe");
    frame.className = "export-preview-print-frame";
    if (isHtml) { frame.setAttribute("sandbox", "allow-same-origin allow-modals"); frame.srcdoc = urlOrHtml; }
    else frame.src = urlOrHtml;
    let cleaned = false;
    const remove = () => { if (cleaned) return; cleaned = true; frame.remove(); onCleanup?.(); };
    const timeout = window.setTimeout(() => { remove(); reject(new Error("print_timeout")); }, 60_000);
    frame.onload = () => {
      window.clearTimeout(timeout);
      try {
        if (!frame.contentWindow) throw new Error("print_unavailable");
        frame.contentWindow.focus(); frame.contentWindow.print(); resolve();
      } catch (error) { remove(); reject(error); }
      frame.contentWindow?.addEventListener("afterprint", remove, { once: true });
      window.setTimeout(remove, 60_000);
    };
    document.body.append(frame);
  });
}

export function ExportPreviewDialog({ blob, fileName, locale = "es", html, formattedWorkbook = false, onDownload, onClose }: Props) {
  const t = labels[locale] ?? labels.es;
  const dialogRef = useRef<HTMLDialogElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const viewportRef = useRef<HTMLDivElement>(null);
  const pdfRef = useRef<PDFDocumentProxy | null>(null);
  const loadingTaskRef = useRef<PDFDocumentLoadingTask | null>(null);
  const printControllerRef = useRef<AbortController | null>(null);
  const outcomeRef = useRef<ExportPreviewOutcome>({ downloaded: false, printed: false, canceled: true });
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [message, setMessage] = useState("");
  const [sheets, setSheets] = useState<PreviewSheet[]>([]);
  const [sheetIndex, setSheetIndex] = useState(0);
  const [page, setPage] = useState(1);
  const [pageDraft, setPageDraft] = useState("1");
  const [totalPages, setTotalPages] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [fitScale, setFitScale] = useState(1);
  const [viewportWidth, setViewportWidth] = useState(850);
  const [fit, setFit] = useState(true);
  const [busy, setBusy] = useState(false);
  const [visibleWorkbookRows, setVisibleWorkbookRows] = useState(WORKBOOK_ROW_BATCH);
  const kind = html ? "html" : /\.pdf$/i.test(fileName) ? "pdf" : "workbook";
  const visibleZoom = fit ? fitScale : zoom;
  const visibleZoomRef = useRef(visibleZoom);

  useEffect(() => { visibleZoomRef.current = visibleZoom; }, [visibleZoom]);

  const changeZoom = useCallback((direction: -1 | 1) => {
    const next = Math.min(2.5, Math.max(.25, +(visibleZoomRef.current + direction * .1).toFixed(2)));
    visibleZoomRef.current = next;
    setZoom(next);
    setFit(false);
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (status !== "ready" || !dialog) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey || event.deltaY === 0) return;
      event.preventDefault();
      event.stopPropagation();
      changeZoom(event.deltaY < 0 ? 1 : -1);
    };
    // A non-passive listener prevents Ctrl+wheel from zooming the entire application.
    dialog.addEventListener("wheel", onWheel, { passive: false });
    const frame = dialog.querySelector<HTMLIFrameElement>(".export-preview-html");
    let frameDocument: Document | null = null;
    const bindFrame = () => {
      frameDocument?.removeEventListener("wheel", onWheel);
      frameDocument = frame?.contentDocument ?? null;
      frameDocument?.addEventListener("wheel", onWheel, { passive: false });
    };
    frame?.addEventListener("load", bindFrame);
    bindFrame();
    return () => {
      dialog.removeEventListener("wheel", onWheel);
      frame?.removeEventListener("load", bindFrame);
      frameDocument?.removeEventListener("wheel", onWheel);
    };
  }, [status, kind, changeZoom]);

  const close = useCallback(() => { printControllerRef.current?.abort(); if (dialogRef.current?.open) dialogRef.current.close(); onClose(outcomeRef.current); }, [onClose]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    dialog.showModal();
    dialog.querySelector<HTMLElement>("[data-preview-close]")?.focus();
    const releaseFocusTrap = activateModalFocusTrap(dialog as unknown as ModalFocusRoot, document, { restoreFocus: false });
    const onCancel = (event: Event) => { event.preventDefault(); event.stopPropagation(); close(); };
    dialog.addEventListener("cancel", onCancel);
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopImmediatePropagation(); close(); }
      else if ((event.ctrlKey || event.metaKey) && ["s", "p"].includes(event.key.toLowerCase())) {
        event.preventDefault(); event.stopImmediatePropagation();
        dialog.querySelector<HTMLButtonElement>(event.key.toLowerCase() === "s" ? "[data-preview-download]" : "[data-preview-print]")?.click();
      } else if (/^F\d{1,2}$/.test(event.key)) {
        event.preventDefault(); event.stopImmediatePropagation();
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => { printControllerRef.current?.abort(); releaseFocusTrap(); dialog.removeEventListener("cancel", onCancel); window.removeEventListener("keydown", onKey, true); if (dialog.open) dialog.close(); };
  }, [close]);

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        if (kind === "pdf") {
          const [pdfjs, worker] = await Promise.all([import("pdfjs-dist"), import("pdfjs-dist/build/pdf.worker.min.mjs?url")]);
          if (!active) return;
          pdfjs.GlobalWorkerOptions.workerSrc = worker.default;
          const bytes = new Uint8Array(await blob.arrayBuffer());
          if (!active) return;
          const loadingTask = pdfjs.getDocument({ data: bytes });
          loadingTaskRef.current = loadingTask;
          const document = await loadingTask.promise;
          if (!active) { void loadingTask.destroy(); return; }
          pdfRef.current = document;
          setTotalPages(document.numPages);
        } else if (kind === "workbook") {
          const result = await readWorkbookPreview(blob, fileName, formattedWorkbook);
          if (!active) return;
          setSheets(result);
          setVisibleWorkbookRows(WORKBOOK_ROW_BATCH);
        }
        if (active) setStatus("ready");
      } catch (error) {
        if (active) { setStatus("error"); setMessage(error instanceof Error && error.message === "preview_too_large" ? t.tooLarge : t.failed); }
      }
    }
    void load();
    return () => { active = false; const loadingTask = loadingTaskRef.current; loadingTaskRef.current = null; if (loadingTask) void loadingTask.destroy(); pdfRef.current = null; };
  }, [blob, fileName, formattedWorkbook, kind, t.failed, t.tooLarge]);

  useEffect(() => {
    if (kind !== "pdf" || status !== "ready" || !pdfRef.current || !canvasRef.current) return;
    let active = true;
    let task: RenderTask | undefined;
    async function renderPage() {
      const pdf = pdfRef.current;
      const canvas = canvasRef.current;
      if (!pdf || !canvas) return;
      try {
        const pdfPage = await pdf.getPage(page);
        if (!active) return;
        const base = pdfPage.getViewport({ scale: 1 });
        const naturalFit = Math.min(2.5, 794 / base.width, Math.max(0.25, (viewportWidth - 64) / base.width));
        setFitScale(naturalFit);
        const scale = fit ? naturalFit : zoom;
        const viewport = pdfPage.getViewport({ scale });
        const ratio = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(16_000_000 / (viewport.width * viewport.height)));
        canvas.width = Math.ceil(viewport.width * ratio);
        canvas.height = Math.ceil(viewport.height * ratio);
        canvas.style.width = `${viewport.width}px`;
        canvas.style.height = `${viewport.height}px`;
        const context = canvas.getContext("2d");
        if (!context) throw new Error("canvas_unavailable");
        task = pdfPage.render({ canvasContext: context, canvas, viewport, transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] });
        await task.promise;
      } catch (error) { if (active && !(error instanceof Error && error.name === "RenderingCancelledException")) setMessage(t.failed); }
    }
    void renderPage();
    return () => { active = false; task?.cancel(); };
  }, [kind, status, page, zoom, fit, viewportWidth, t.failed]);

  useEffect(() => {
    const stage = viewportRef.current;
    if (!stage) return;
    const measure = () => setViewportWidth(stage.clientWidth || 850);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(stage);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (status !== "ready" || kind === "pdf") return;
    const measure = () => {
      if (kind === "workbook") { setFitScale(1); return; }
      setFitScale(Math.min(2.5, Math.max(0.25, (viewportWidth - 56) / 850)));
    };
    measure();
  }, [kind, status, viewportWidth]);

  const sheet = sheets[sheetIndex];
  const workbookColumns = sheet?.format ? workbookColumnCount(sheet) : undefined;
  const workbookColumnWidths = Array.from({ length: workbookColumns ?? 0 },
    (_, index) => sheet?.format?.columnWidths[index] ?? 80);
  const workbookDataWidth = workbookColumnWidths.reduce((sum, width) => sum + width, 0);
  const workbookPaperWidth = Math.max(794, viewportWidth - 112,
    workbookDataWidth + WORKBOOK_ROW_NUMBER_WIDTH + 52);
  const effectivePages = totalPages;
  const goToPage = (next: number) => { const bounded = Math.max(1, Math.min(effectivePages, Number.isFinite(next) ? next : 1)); setPage(bounded); setPageDraft(String(bounded)); };
  const selectSheet = (index: number) => { setSheetIndex(index); setVisibleWorkbookRows(WORKBOOK_ROW_BATCH); if (viewportRef.current) { viewportRef.current.scrollTop = 0; viewportRef.current.scrollLeft = 0; } };
  const loadMoreWorkbookRows = () => {
    if (kind !== "workbook" || status !== "ready" || !sheet || visibleWorkbookRows >= sheet.rows.length) return;
    const stage = viewportRef.current;
    if (stage && stage.scrollTop + stage.clientHeight >= stage.scrollHeight - 400) {
      setVisibleWorkbookRows((current) => Math.min(sheet.rows.length, current + WORKBOOK_ROW_BATCH));
    }
  };

  useEffect(() => {
    if (kind !== "workbook" || status !== "ready" || !sheet || visibleWorkbookRows >= sheet.rows.length) return;
    const stage = viewportRef.current;
    if (stage && stage.clientHeight > 0 && stage.scrollHeight <= stage.clientHeight) {
      setVisibleWorkbookRows((current) => Math.min(sheet.rows.length, current + WORKBOOK_ROW_BATCH));
    }
  }, [kind, status, sheet, visibleWorkbookRows]);

  async function download() {
    if (busy) return;
    setBusy(true); setMessage("");
    try {
      if (onDownload) {
        const result = await onDownload();
        if (result.canceled) { setMessage(t.canceled); return; }
      } else if (window.tpvDesktop?.reports) {
        const extension = fileName.split(".").pop()?.toLowerCase() || "pdf";
        const result = await window.tpvDesktop.reports.saveFile({
          defaultFileName: fileName,
          filters: [{ name: extension.toUpperCase(), extensions: [extension] }],
          bytes: new Uint8Array(await blob.arrayBuffer())
        });
        if (!result.ok) throw new Error(result.message);
        if (result.canceled) { setMessage(t.canceled); return; }
      } else {
        const url = URL.createObjectURL(blob);
        try {
          const anchor = document.createElement("a");
          anchor.href = url; anchor.download = fileName;
          document.body.append(anchor); anchor.click(); anchor.remove();
        } finally { window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
      }
      outcomeRef.current = { ...outcomeRef.current, downloaded: true, canceled: false };
      setMessage(t.saved);
    } catch (error) { setMessage(error instanceof Error ? error.message : t.failed); }
    finally { setBusy(false); }
  }

  async function print() {
    if (busy || status !== "ready") return;
    const controller = new AbortController();
    printControllerRef.current = controller;
    setBusy(true); setMessage("");
    try {
      const printHtml = kind === "workbook" ? workbookPrintHtml(sheets, fileName)
        : kind === "pdf" && pdfRef.current ? await pdfPrintHtml(pdfRef.current, fileName, controller.signal) : html;
      if (controller.signal.aborted || !printHtml) return;
      const reports = window.tpvDesktop?.reports as (DesktopExportPrint & NonNullable<NonNullable<Window["tpvDesktop"]>["reports"]>) | undefined;
      if (reports?.printExport) {
        const result = await reports.printExport({ html: printHtml });
        if (!result.ok) throw new Error(result.message || t.failed);
        if (result.canceled) return;
      } else await browserPrint(printHtml, true);
      if (controller.signal.aborted) return;
      outcomeRef.current = { ...outcomeRef.current, printed: true, canceled: false };
      setMessage(t.printed);
    } catch (error) { if (!controller.signal.aborted) setMessage(error instanceof Error ? error.message : t.failed); }
    finally { if (printControllerRef.current === controller) printControllerRef.current = null; if (!controller.signal.aborted) setBusy(false); }
  }

  return <dialog ref={dialogRef} className="export-preview-dialog" role="dialog" aria-modal="true" aria-label={t.title} onKeyDown={(event) => event.stopPropagation()}>
    <header className="export-preview-header"><strong>{t.title}</strong><span title={fileName}>{fileName}</span><button type="button" data-preview-close aria-label={t.close} onClick={close}><X size={18} /></button></header>
    <main className="export-preview-stage" ref={viewportRef} onScroll={loadMoreWorkbookRows}>
      {status === "loading" && <div className="export-preview-notice" role="status">{t.loading}</div>}
      {status === "error" && <div className="export-preview-notice" role="alert">{message}</div>}
      {status === "ready" && kind === "pdf" && <canvas ref={canvasRef} className="export-preview-paper" aria-label={`${t.page} ${page}`} />}
      {status === "ready" && kind === "html" && <div className="export-preview-html-holder" style={{ width: 850 * visibleZoom, height: 1120 * visibleZoom }}><iframe className="export-preview-paper export-preview-html" title={fileName} sandbox="allow-same-origin" srcDoc={html} style={{ transform: `scale(${visibleZoom})` }} /></div>}
      {status === "ready" && kind === "workbook" && <div className={`export-preview-paper export-preview-workbook${sheet?.format ? " export-preview-workbook-formatted" : ""}`} style={{ zoom: visibleZoom, width: sheet?.format ? workbookPaperWidth : undefined }}>
        <table style={sheet?.format ? { minWidth: workbookDataWidth + WORKBOOK_ROW_NUMBER_WIDTH } : undefined}>
          <colgroup><col style={{ width: WORKBOOK_ROW_NUMBER_WIDTH }} />{workbookColumnWidths.map((width, index) =>
            <col key={index} style={{ width: width / workbookDataWidth * (workbookPaperWidth - 52 - WORKBOOK_ROW_NUMBER_WIDTH - 1) }} />)}</colgroup>
          <tbody>{sheet?.rows.slice(0, visibleWorkbookRows).map((row, rowIndex) => <tr key={rowIndex} style={sheet.format?.rowHeights[rowIndex] ? { height: sheet.format.rowHeights[rowIndex] } : undefined}>
            <th scope="row">{rowIndex + 1}</th>{workbookRowCells(sheet, row, rowIndex, workbookColumns).map((cell, columnIndex) => <td key={columnIndex} colSpan={cell.colSpan > 1 ? cell.colSpan : undefined} style={cell.style ? { fontSize: cell.style.fontSize, fontWeight: cell.style.bold ? 700 : undefined, color: cell.style.color, backgroundColor: cell.style.background, textAlign: cell.style.align } : undefined}>{cell.value}</td>)}
          </tr>)}</tbody>
        </table>
      </div>}
    </main>
    {status === "ready" && <nav className="export-preview-toolbar" aria-label={t.title}>
      {kind === "workbook" && sheets.length > 1 && <label>{t.sheet}<select value={sheetIndex} onChange={(event) => selectSheet(Number(event.target.value))}>{sheets.map((item, index) => <option key={index} value={index}>{item.name}</option>)}</select></label>}
      <button type="button" aria-label={t.zoomOut} disabled={visibleZoom <= .25} onClick={() => changeZoom(-1)}><MagnifyingGlassMinus size={17} /></button>
      <span>{Math.round(visibleZoom * 100)}%</span>
      <button type="button" aria-label={t.zoomIn} disabled={visibleZoom >= 2.5} onClick={() => changeZoom(1)}><MagnifyingGlassPlus size={17} /></button>
      <button type="button" aria-label={t.fit} aria-pressed={fit} onClick={() => setFit(true)}><ArrowsIn size={17} /></button>
      <i />
      {kind !== "workbook" && <><button type="button" aria-label={t.first} disabled={page <= 1} onClick={() => goToPage(1)}><ArrowLeft size={17} weight="bold" /></button>
      <button type="button" aria-label={t.previous} disabled={page <= 1} onClick={() => goToPage(page - 1)}><ArrowLeft size={17} /></button>
      <label>{t.page}<input aria-label={t.page} type="number" min="1" max={effectivePages} value={pageDraft} onChange={(event) => setPageDraft(event.target.value)} onBlur={() => goToPage(Number(pageDraft))} onKeyDown={(event) => { if (event.key === "Enter") { goToPage(Number(pageDraft)); event.currentTarget.blur(); } }} /></label><span>{t.of} {effectivePages}</span>
      <button type="button" aria-label={t.next} disabled={page >= effectivePages} onClick={() => goToPage(page + 1)}><ArrowRight size={17} /></button>
      <button type="button" aria-label={t.last} disabled={page >= effectivePages} onClick={() => goToPage(effectivePages)}><ArrowRight size={17} weight="bold" /></button></>}
    </nav>}
    <footer className="export-preview-footer"><span role="status">{status === "ready" ? message : ""}</span><button type="button" data-preview-download disabled={busy} onClick={() => void download()}><DownloadSimple size={17} />{t.download}</button><button type="button" data-preview-print disabled={busy || status !== "ready"} onClick={() => void print()}><Printer size={17} />{t.print}</button></footer>
  </dialog>;
}
