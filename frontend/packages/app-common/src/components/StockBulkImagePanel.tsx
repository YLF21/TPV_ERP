import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState
} from "react";
import type { InputHTMLAttributes, KeyboardEvent } from "react";
import type { LocaleCode } from "../types";
import { apiBaseUrl } from "../api/runtime";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { StockInventoryRow } from "./StockScreen";
import { matchStockBulkImageFiles } from "./stockBulkAdvanced";
import { enterNavigationIntent, focusRelativeEnterTarget } from "./keyboardNavigation";
import { ErpSelect } from "./ErpSelect";

export type StockBulkImageStatus =
  | "pending"
  | "matched"
  | "noMatch"
  | "multipleMatches"
  | "productAlreadyMatched"
  | "uploading"
  | "uploaded"
  | "error";

export type StockBulkImageFileRow = {
  id: string;
  persistedId?: string;
  selected: boolean;
  file: File;
  fileName: string;
  fileType: string;
  relativePath: string;
  productId: string;
  status: StockBulkImageStatus;
  error?: string;
};

export type StockBulkImageSnapshot = {
  rows: StockBulkImageFileRow[];
};

export type StockBulkImageAssignment = {
  rowId: string;
  productId: string;
  file: File;
};

export type StockBulkImageAppliedImage = {
  rowId: string;
  productId: string;
  imageId: string | null;
};

export type StockBulkImageApplyFailure = {
  rowId: string;
  productId: string;
  error: string;
};

export type StockBulkImageApplyResult = {
  attempted: number;
  uploaded: StockBulkImageAppliedImage[];
  failed: StockBulkImageApplyFailure[];
  unassignedRowIds: string[];
  snapshot: StockBulkImageSnapshot;
};

export type StockBulkDraftImageView = {
  id: string;
  productId: string | null;
  position: number;
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
};

export type StockBulkDraftImageSyncView = {
  version: number;
  images: StockBulkDraftImageView[];
};

export type StockBulkImagePanelHandle = {
  openFolder: () => void;
  compareByName: () => void;
  compareByCode: () => void;
  getSnapshot: () => StockBulkImageSnapshot;
  restoreSnapshot: (snapshot: StockBulkImageSnapshot) => void;
  clear: () => void;
  hasChanges: () => boolean;
  getPendingAssignments: () => StockBulkImageAssignment[];
  applyPending: (token: string) => Promise<StockBulkImageApplyResult>;
};

export type StockBulkImagePanelProps = {
  locale: LocaleCode;
  products: StockInventoryRow[];
  snapshot?: StockBulkImageSnapshot;
  onSnapshotChange?: (snapshot: StockBulkImageSnapshot) => void;
  onPendingImagesChange?: (assignments: Array<{ productId: string; file: File }>) => void;
  onUploaded?: (productId: string, imageId: string | null) => void;
  onStatus: (message: string) => void;
  showToolbar?: boolean;
  /** Compatibility property until StockScreen invokes applyPending(token) through the ref. */
  token?: string;
};

const directoryInputAttributes = {
  webkitdirectory: ""
} as InputHTMLAttributes<HTMLInputElement>;

export function stockBulkImageRows(files: File[]) {
  return files
    .filter((file) => file.type.startsWith("image/") || /\.(avif|bmp|gif|jpe?g|png|webp)$/i.test(file.name))
    .map((file, index): StockBulkImageFileRow => ({
      id: `${file.webkitRelativePath || file.name}-${file.size}-${index}`,
      selected: false,
      file,
      fileName: file.name,
      fileType: file.type || file.name.split(".").at(-1)?.toLocaleUpperCase() || "-",
      relativePath: file.webkitRelativePath || file.name,
      productId: "",
      status: "pending"
    }));
}

export function createStockBulkImageSnapshot(rows: readonly StockBulkImageFileRow[]): StockBulkImageSnapshot {
  return { rows: rows.map((row) => ({ ...row })) };
}

export function cloneStockBulkImageSnapshot(snapshot: StockBulkImageSnapshot): StockBulkImageSnapshot {
  return createStockBulkImageSnapshot(snapshot.rows);
}

export function stockBulkImagePendingAssignments(
  snapshot: StockBulkImageSnapshot
): StockBulkImageAssignment[] {
  return snapshot.rows.flatMap((row) => (
    row.productId && row.status !== "uploaded"
      ? [{ rowId: row.id, productId: row.productId, file: row.file }]
      : []
  ));
}

async function stockBulkImageHttpError(response: Response) {
  let message = response.statusText || "bulk_image_request_error";
  try {
    const problem = await response.json() as { detail?: string; code?: string };
    message = problem.detail || problem.code || message;
  } catch {
    // Keep the HTTP status text when the backend does not return a problem body.
  }
  return new Error(message);
}

export async function syncStockBulkDraftImages(
  draftId: string,
  version: number,
  snapshot: StockBulkImageSnapshot,
  token: string,
  request: typeof fetch = fetch,
  apiRoot = apiBaseUrl
) {
  const files: File[] = [];
  const manifest = {
    version,
    images: snapshot.rows.map((row) => {
      const item: { id?: string; productId: string | null; fileIndex?: number } = {
        productId: row.productId || null
      };
      if (row.persistedId) {
        item.id = row.persistedId;
      } else {
        item.fileIndex = files.push(row.file) - 1;
      }
      return item;
    })
  };
  const body = new FormData();
  body.append("manifest", new Blob([JSON.stringify(manifest)], { type: "application/json" }), "manifest.json");
  files.forEach((file) => body.append("files", file, file.name));
  const response = await request(
    `${apiRoot}/product-bulk-edits/${encodeURIComponent(draftId)}/images`,
    {
      method: "PUT",
      headers: { Authorization: `Bearer ${token}` },
      body
    }
  );
  if (!response.ok) throw await stockBulkImageHttpError(response);
  const result = await response.json() as StockBulkDraftImageSyncView;
  const images = [...result.images].sort((left, right) => left.position - right.position);
  if (images.length !== snapshot.rows.length) {
    throw new Error("bulk_image_sync_count_mismatch");
  }
  return {
    version: result.version,
    snapshot: createStockBulkImageSnapshot(snapshot.rows.map((row, index) => ({
      ...row,
      persistedId: images[index].id,
      productId: images[index].productId ?? "",
      status: images[index].productId ? "matched" : "pending",
      error: undefined
    })))
  };
}

export async function loadStockBulkDraftImages(
  draftId: string,
  token: string,
  request: typeof fetch = fetch,
  apiRoot = apiBaseUrl
): Promise<StockBulkImageSnapshot> {
  const headers = { Authorization: `Bearer ${token}` };
  const response = await request(
    `${apiRoot}/product-bulk-edits/${encodeURIComponent(draftId)}/images`,
    { headers }
  );
  if (!response.ok) throw await stockBulkImageHttpError(response);
  const images = (await response.json() as StockBulkDraftImageView[])
    .sort((left, right) => left.position - right.position);
  const rows = await Promise.all(images.map(async (image) => {
    const contentResponse = await request(
      `${apiRoot}/product-bulk-edits/${encodeURIComponent(draftId)}/images/${encodeURIComponent(image.id)}/content`,
      { headers }
    );
    if (!contentResponse.ok) throw await stockBulkImageHttpError(contentResponse);
    const blob = await contentResponse.blob();
    const file = new File([blob], image.fileName, { type: image.contentType });
    return {
      id: `bulk-image-${image.id}`,
      persistedId: image.id,
      selected: false,
      file,
      fileName: image.fileName,
      fileType: image.contentType,
      relativePath: image.fileName,
      productId: image.productId ?? "",
      status: image.productId ? "matched" as const : "pending" as const
    } satisfies StockBulkImageFileRow;
  }));
  return createStockBulkImageSnapshot(rows);
}

export async function uploadStockBulkImage(
  productId: string,
  file: File,
  token: string,
  request: typeof fetch = fetch
) {
  const body = new FormData();
  body.append("file", file);
  const response = await request(
    `${apiBaseUrl}/products/${encodeURIComponent(productId)}/image`,
    {
      method: "PUT",
      headers: { Authorization: `Bearer ${token}` },
      body
    }
  );
  if (!response.ok) {
    let message = response.statusText || "image_upload_error";
    try {
      const problem = await response.json() as { detail?: string; code?: string };
      message = problem.detail || problem.code || message;
    } catch {
      // Keep the HTTP status text when the backend does not return a problem body.
    }
    throw new Error(message);
  }
  const product = await response.json() as { imageId?: string | null };
  return product.imageId ?? null;
}

export async function applyStockBulkImageSnapshot(
  source: StockBulkImageSnapshot,
  token: string,
  request: typeof fetch = fetch
): Promise<StockBulkImageApplyResult> {
  const snapshot = cloneStockBulkImageSnapshot(source);
  const assignments = stockBulkImagePendingAssignments(snapshot);
  const uploaded: StockBulkImageAppliedImage[] = [];
  const failed: StockBulkImageApplyFailure[] = [];

  for (const assignment of assignments) {
    const rowIndex = snapshot.rows.findIndex((row) => row.id === assignment.rowId);
    try {
      const imageId = await uploadStockBulkImage(
        assignment.productId,
        assignment.file,
        token,
        request
      );
      snapshot.rows[rowIndex] = {
        ...snapshot.rows[rowIndex],
        status: "uploaded",
        error: undefined
      };
      uploaded.push({
        rowId: assignment.rowId,
        productId: assignment.productId,
        imageId
      });
    } catch (caught) {
      const error = caught instanceof Error ? caught.message : "image_upload_error";
      snapshot.rows[rowIndex] = {
        ...snapshot.rows[rowIndex],
        status: "error",
        error
      };
      failed.push({ rowId: assignment.rowId, productId: assignment.productId, error });
    }
  }

  return {
    attempted: assignments.length,
    uploaded,
    failed,
    unassignedRowIds: snapshot.rows
      .filter((row) => !row.productId && row.status !== "uploaded")
      .map((row) => row.id),
    snapshot
  };
}

function ImagePreview({ file }: { file: File }) {
  const [source, setSource] = useState("");
  useEffect(() => {
    const url = URL.createObjectURL(file);
    setSource(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);
  return source ? <img alt="" src={source} /> : null;
}

export const StockBulkImagePanel = forwardRef<StockBulkImagePanelHandle, StockBulkImagePanelProps>(
  function StockBulkImagePanel({
    locale,
    products,
    snapshot: controlledSnapshot,
    onSnapshotChange,
    onPendingImagesChange,
    onUploaded,
    onStatus,
    showToolbar = true
  }, ref) {
    const t = useMemo(() => createTranslator(locale), [locale]);
    const panelRef = useRef<HTMLElement | null>(null);
    const inputRef = useRef<HTMLInputElement | null>(null);
    const [internalRows, setInternalRows] = useState<StockBulkImageFileRow[]>(
      () => cloneStockBulkImageSnapshot(controlledSnapshot ?? { rows: [] }).rows
    );
    const rows = controlledSnapshot?.rows ?? internalRows;
    const rowsRef = useRef<StockBulkImageFileRow[]>(createStockBulkImageSnapshot(rows).rows);
    const busyRef = useRef(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const productById = useMemo(
      () => new Map(products.map((product) => [product.productId, product])),
      [products]
    );

    useEffect(() => {
      rowsRef.current = createStockBulkImageSnapshot(rows).rows;
    }, [rows]);

    const commitRows = useCallback((
      update: (current: StockBulkImageFileRow[]) => StockBulkImageFileRow[]
    ) => {
      const nextRows = createStockBulkImageSnapshot(update(rowsRef.current)).rows;
      rowsRef.current = nextRows;
      if (controlledSnapshot === undefined) {
        setInternalRows(nextRows);
      }
      onSnapshotChange?.(createStockBulkImageSnapshot(nextRows));
    }, [controlledSnapshot, onSnapshotChange]);

    useEffect(() => {
      onPendingImagesChange?.(stockBulkImagePendingAssignments(
        createStockBulkImageSnapshot(rows)
      ).map(({ productId, file }) => ({ productId, file })));
    }, [onPendingImagesChange, rows]);

    const compare = useCallback((mode: "code" | "name") => {
      const currentRows = rowsRef.current;
      const selectedRows = currentRows.filter((row) => row.selected);
      const comparedRows = selectedRows.length > 0 ? selectedRows : currentRows;
      const result = matchStockBulkImageFiles(
        comparedRows.map((row) => ({ name: row.fileName, file: row.file })),
        products,
        mode
      );
      const comparedFiles = new Set(comparedRows.map((row) => row.file));
      const matches = new Map(result.matches.map((match) => [match.file, match]));
      const unresolved = new Map(result.unresolved.map((value) => [value.file, value]));
      commitRows((current) => current.map((row) => {
        if (!comparedFiles.has(row.file)) {
          return row;
        }
        const match = matches.get(row.file);
        if (match) {
          return { ...row, productId: match.productId, status: "matched", error: undefined };
        }
        const pending = unresolved.get(row.file);
        return {
          ...row,
          productId: "",
          status: pending?.reason ?? "noMatch",
          error: undefined
        };
      }));
      setError("");
      onStatus(t("stock.bulkEdit.images.compared")
        .replace("{matched}", String(result.matches.length))
        .replace("{pending}", String(result.unresolved.length)));
    }, [commitRows, onStatus, products, t]);

    const assign = (rowId: string, productId: string) => {
      commitRows((current) => current.map((row) => row.id === rowId
        ? { ...row, productId, status: productId ? "matched" : "pending", error: undefined }
        : row));
    };

    const applyPending = useCallback(async (token: string) => {
      if (busyRef.current) {
        throw new Error("stock_bulk_image_apply_in_progress");
      }

      const source = createStockBulkImageSnapshot(rowsRef.current);
      const assignments = stockBulkImagePendingAssignments(source);
      if (assignments.length === 0) {
        if (source.rows.length > 0) {
          setError(t("stock.bulkEdit.images.assignFirst"));
        }
        return {
          attempted: 0,
          uploaded: [],
          failed: [],
          unassignedRowIds: source.rows
            .filter((row) => !row.productId && row.status !== "uploaded")
            .map((row) => row.id),
          snapshot: source
        } satisfies StockBulkImageApplyResult;
      }

      busyRef.current = true;
      setBusy(true);
      setError("");
      const assignmentIds = new Set(assignments.map((assignment) => assignment.rowId));
      commitRows((current) => current.map((row) => assignmentIds.has(row.id)
        ? { ...row, status: "uploading", error: undefined }
        : row));

      try {
        const result = await applyStockBulkImageSnapshot(source, token);
        commitRows(() => result.snapshot.rows);
        result.uploaded.forEach((image) => onUploaded?.(image.productId, image.imageId));
        if (result.failed.length > 0) {
          setError(t("stock.bulkEdit.images.uploadError"));
        }
        onStatus(t("stock.bulkEdit.images.uploaded")
          .replace("{uploaded}", String(result.uploaded.length))
          .replace("{total}", String(result.attempted)));
        return result;
      } finally {
        busyRef.current = false;
        setBusy(false);
      }
    }, [commitRows, onStatus, onUploaded, t]);

    useImperativeHandle(ref, () => ({
      openFolder: () => inputRef.current?.click(),
      compareByName: () => compare("name"),
      compareByCode: () => compare("code"),
      getSnapshot: () => createStockBulkImageSnapshot(rowsRef.current),
      restoreSnapshot: (snapshot) => {
        commitRows(() => cloneStockBulkImageSnapshot(snapshot).rows);
        setError("");
      },
      clear: () => {
        commitRows(() => []);
        setError("");
      },
      hasChanges: () => rowsRef.current.length > 0,
      getPendingAssignments: () => stockBulkImagePendingAssignments(
        createStockBulkImageSnapshot(rowsRef.current)
      ),
      applyPending
    }), [applyPending, commitRows, compare]);

    const assignedProducts = new Map<string, string>();
    rows.forEach((row) => {
      if (row.productId) assignedProducts.set(row.productId, row.id);
    });
    const allRowsSelected = rows.length > 0 && rows.every((row) => row.selected);

    function handleEntryKeyDown(event: KeyboardEvent<HTMLElement>) {
      const intent = enterNavigationIntent(event.key, {
        shiftKey: event.shiftKey,
        ctrlKey: event.ctrlKey,
        altKey: event.altKey,
        metaKey: event.metaKey,
        isComposing: event.nativeEvent.isComposing
      });
      if (!intent) return;
      event.preventDefault();
      if (intent === "next" && event.currentTarget.matches("input[type='checkbox']")) {
        (event.currentTarget as HTMLInputElement).click();
      }
      focusRelativeEnterTarget(
        panelRef.current,
        event.currentTarget,
        intent,
        "[data-image-entry]:not(:disabled), .stock-bulk-image-product-select .erp-select__trigger:not(:disabled)"
      );
    }

    function moveFromActiveProductSelect(intent: "next" | "previous") {
      const current = document.activeElement;
      if (!(current instanceof HTMLElement)) return;
      focusRelativeEnterTarget(
        panelRef.current,
        current,
        intent,
        "[data-image-entry]:not(:disabled), .stock-bulk-image-product-select .erp-select__trigger:not(:disabled)"
      );
    }

    return (
      <section ref={panelRef} className="stock-bulk-image-panel" aria-label={t("stock.bulkEdit.images.files") }>
        <input
          {...directoryInputAttributes}
          ref={inputRef}
          className="bulk-file-input"
          type="file"
          accept="image/*"
          multiple
          onChange={(event) => {
            const next = stockBulkImageRows(Array.from(event.target.files ?? []));
            commitRows(() => next);
            setError(next.length ? "" : t("stock.bulkEdit.images.noFiles"));
            onStatus(t("stock.bulkEdit.images.loaded").replace("{count}", String(next.length)));
            event.currentTarget.value = "";
          }}
        />
        {showToolbar && (
          <header className="stock-bulk-image-actions">
            <strong>{t("stock.bulkEdit.images.files")}</strong>
            <span>{t("stock.bulkEdit.images.count").replace("{count}", String(rows.length))}</span>
            <button type="button" disabled={busy} onClick={() => inputRef.current?.click()}>{t("stock.bulkEdit.openFolder")}</button>
            <button type="button" disabled={busy || rows.length === 0} onClick={() => compare("name")}>{t("stock.bulkEdit.compareName")}</button>
            <button type="button" disabled={busy || rows.length === 0} onClick={() => compare("code")}>{t("stock.bulkEdit.compareCode")}</button>
          </header>
        )}
        {error && <p className="stock-bulk-dialog-error" role="alert">{error}</p>}
        <div className="stock-bulk-image-table" role="table">
          <div className="stock-bulk-image-row head" role="row">
            <label className="bulk-check">
              <input
                data-image-entry
                type="checkbox"
                aria-label={t("stock.bulkEdit.selectAll")}
                checked={allRowsSelected}
                disabled={rows.length === 0}
                onChange={(event) => commitRows((current) => current.map((row) => ({ ...row, selected: event.target.checked })))}
                onKeyDown={handleEntryKeyDown}
              />
            </label>
            <span>{t("stock.bulkEdit.images.preview")}</span>
            <span>{t("stock.bulkEdit.images.file")}</span>
            <span>{t("stock.bulkEdit.images.fileType")}</span>
            <span>{t("stock.bulkEdit.images.product")}</span>
            <span style={{ gridColumn: "span 2" }}>{t("stock.bulkEdit.images.state")}</span>
          </div>
          {rows.length === 0 && <p className="stock-empty-state">{t("stock.bulkEdit.images.empty")}</p>}
          {rows.map((row) => {
            const product = productById.get(row.productId);
            return (
              <div className={`stock-bulk-image-row ${row.status}`} key={row.id} role="row">
                <label className="bulk-check">
                  <input
                    data-image-entry
                    type="checkbox"
                    aria-label={`${t("stock.bulkEdit.images.file")}: ${row.fileName}`}
                    checked={row.selected}
                    disabled={busy}
                    onChange={(event) => commitRows((current) => current.map((value) => value.id === row.id
                      ? { ...value, selected: event.target.checked }
                      : value))}
                    onKeyDown={handleEntryKeyDown}
                  />
                </label>
                <span className="stock-bulk-file-preview"><ImagePreview file={row.file} /></span>
                <span title={row.relativePath}><strong>{row.fileName}</strong><small>{row.relativePath}</small></span>
                <span>{row.fileType}</span>
                <div>
                  <ErpSelect
                    className="stock-bulk-image-product-select"
                    aria-label={t("stock.bulkEdit.images.product")}
                    value={row.productId}
                    disabled={busy || row.status === "uploaded"}
                    options={[
                      { value: "", label: t("stock.bulkEdit.images.manual") },
                      ...products.map((option) => ({
                        value: option.productId,
                        label: `${option.code || option.barcode || option.barcode2 || "-"} - ${option.name}`,
                        disabled: Boolean(assignedProducts.get(option.productId) && assignedProducts.get(option.productId) !== row.id)
                      }))
                    ]}
                    onChange={(value) => assign(row.id, value)}
                    onCommit={() => moveFromActiveProductSelect("next")}
                    onNavigatePrevious={() => moveFromActiveProductSelect("previous")}
                  />
                </div>
                <span className="stock-bulk-image-state" style={{ gridColumn: "span 2" }}>
                  {t(`stock.bulkEdit.images.status.${row.status}`)}
                  {product && <small>{product.name}</small>}
                  {row.error && <small>{row.error}</small>}
                </span>
              </div>
            );
          })}
        </div>
      </section>
    );
  }
);
