import { createRoot } from "react-dom/client";
import type { LocaleCode } from "../types";

export type ExportPreviewRequest = {
  blob: Blob;
  fileName: string;
  locale?: LocaleCode;
  html?: string;
  /** Render XLSX workbook formatting in the preview and print output. Defaults to the plain table. */
  formattedWorkbook?: boolean;
  onDownload?: () => Promise<{ canceled?: boolean }>;
};

export type ExportPreviewOutcome = { downloaded: boolean; printed: boolean; canceled: boolean };

export async function previewExport(request: ExportPreviewRequest): Promise<ExportPreviewOutcome> {
  if (typeof document === "undefined") throw new Error("Export preview requires a browser window");
  const { ExportPreviewDialog } = await import("./ExportPreviewDialog");
  const mount = document.createElement("div");
  mount.className = "export-preview-mount";
  document.body.append(mount);
  const priorFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const root = createRoot(mount);
  return new Promise<ExportPreviewOutcome>((resolve) => {
    root.render(<ExportPreviewDialog {...request} onClose={(outcome) => {
      // React must finish its close event before the imperative root is removed.
      queueMicrotask(() => {
        root.unmount();
        mount.remove();
        if (priorFocus?.isConnected) priorFocus.focus();
        resolve(outcome);
      });
    }} />);
  });
}
