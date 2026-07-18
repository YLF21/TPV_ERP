import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent
} from "react";
import { apiRequest } from "../api/client";
import type { AppKind, LocaleCode, TerminalContext } from "../types";
import {
  WarehouseDocumentDialog,
  warehouseDocumentPath,
  type WarehouseCustomerOption,
  type WarehouseDocumentMode,
  type WarehouseDocumentView,
  type WarehouseOption,
  type WarehouseSupplierOption
} from "./WarehouseDocumentDialog";
import type { WarehouseImportProduct } from "./warehouseDocumentImport";
import { ErpSelect } from "./ErpSelect";
import { TableLayoutHeaderCell } from "./TableLayoutHeaderCell";
import { enterNavigationIntent } from "./keyboardNavigation";
import { visibleTableColumns } from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

export type {
  WarehouseCustomerOption,
  WarehouseDocumentMode,
  WarehouseDocumentView,
  WarehouseOption,
  WarehouseSupplierOption
} from "./WarehouseDocumentDialog";
export type { WarehouseImportProduct } from "./warehouseDocumentImport";

export type WarehouseOperationView = WarehouseDocumentView & {
  customerId?: string | null;
};

export type WarehouseOperationsPanelPermissions = {
  read?: boolean;
  create?: boolean;
  edit?: boolean;
  delete?: boolean;
  canConfirm?: boolean;
};

export type WarehouseOperationsPanelResolvedPermissions = {
  read: boolean;
  create: boolean;
  edit: boolean;
  delete: boolean;
  canConfirm: boolean;
};

export type WarehouseOperationsPanelRequest = typeof apiRequest;

export type WarehouseOperationsPage = {
  items: WarehouseOperationView[];
  nextCursor?: string | null;
  hasMore?: boolean;
};

export type WarehouseOperationsPanelProps = {
  mode: WarehouseDocumentMode;
  app?: AppKind;
  username?: string;
  accessToken?: string;
  token?: string;
  products: WarehouseImportProduct[];
  warehouses: WarehouseOption[];
  customers: WarehouseCustomerOption[];
  suppliers: WarehouseSupplierOption[];
  t: (key: string) => string;
  locale?: LocaleCode;
  terminalContext?: TerminalContext;
  defaultWarehouseId?: string;
  permissions?: WarehouseOperationsPanelPermissions;
  confirmDelete?: (document: WarehouseOperationView) => boolean | Promise<boolean>;
  onCreateDocument?: () => void | Promise<void>;
  onOpenDocument?: (document: WarehouseOperationView) => void | Promise<void>;
  onSaved?: (document: WarehouseDocumentView) => void | Promise<void>;
  onConfirmed?: (document?: WarehouseDocumentView) => void | Promise<void>;
  onDeleted?: (document: WarehouseOperationView) => void | Promise<void>;
  onClose?: () => void | Promise<void>;
  onError?: (error: unknown) => void;
};

const warehouseOperationsColumnDefinitions = [
  { key: "number", defaultWidth: 170 },
  { key: "date", defaultWidth: 120 },
  { key: "counterparty", defaultWidth: 240 },
  { key: "warehouse", defaultWidth: 180 },
  { key: "status", defaultWidth: 130 },
  { key: "lines", defaultWidth: 90 },
  { key: "totalUnits", defaultWidth: 140 }
] as const;

type WarehouseOperationsColumnKey = typeof warehouseOperationsColumnDefinitions[number]["key"];

export type WarehouseOperationsFilterOptions = {
  mode: WarehouseDocumentMode;
  query: string;
  status: string;
  warehouses: WarehouseOption[];
  customers: WarehouseCustomerOption[];
  suppliers: WarehouseSupplierOption[];
};

export type WarehouseOperationsNavigationKey =
  | "ArrowDown"
  | "ArrowLeft"
  | "ArrowRight"
  | "ArrowUp"
  | "End"
  | "Home";

export function warehouseOperationsPath(mode: WarehouseDocumentMode) {
  return warehouseDocumentPath(mode);
}

const WAREHOUSE_OPERATIONS_PAGE_LIMIT = 500;

export function warehouseOperationsPagePath(mode: WarehouseDocumentMode, cursor?: string | null) {
  const params = new URLSearchParams({ limit: String(WAREHOUSE_OPERATIONS_PAGE_LIMIT) });
  if (cursor) {
    params.set("cursor", cursor);
  }
  return `${warehouseOperationsPath(mode)}?${params.toString()}`;
}

export async function warehouseOperationsLoad(
  mode: WarehouseDocumentMode,
  token: string,
  request: WarehouseOperationsPanelRequest = apiRequest
) {
  const documents: WarehouseOperationView[] = [];
  const seenCursors = new Set<string>();
  let cursor: string | null = null;

  while (true) {
    const response: WarehouseOperationsPage | WarehouseOperationView[] = await request<WarehouseOperationsPage | WarehouseOperationView[]>(
      warehouseOperationsPagePath(mode, cursor),
      { token }
    );
    if (Array.isArray(response)) {
      return [...documents, ...response];
    }
    documents.push(...(Array.isArray(response.items) ? response.items : []));
    const nextCursor: string | null = response.nextCursor?.trim() || null;
    if (!response.hasMore || !nextCursor || seenCursors.has(nextCursor)) {
      return documents;
    }
    seenCursors.add(nextCursor);
    cursor = nextCursor;
  }
}

export async function warehouseOperationsDelete(
  mode: WarehouseDocumentMode,
  document: WarehouseOperationView,
  token: string,
  request: WarehouseOperationsPanelRequest = apiRequest
) {
  if (!warehouseOperationsIsDraft(document)) {
    throw new Error("warehouse_operation_not_draft");
  }
  await request<void>(`${warehouseOperationsPath(mode)}/${encodeURIComponent(document.id)}`, {
    token,
    method: "DELETE"
  });
}

export function warehouseOperationsResolvePermissions(
  permissions: WarehouseOperationsPanelPermissions | undefined
): WarehouseOperationsPanelResolvedPermissions {
  return {
    read: permissions?.read ?? true,
    create: permissions?.create ?? true,
    edit: permissions?.edit ?? true,
    delete: permissions?.delete ?? true,
    canConfirm: permissions?.canConfirm ?? false
  };
}

export function warehouseOperationsIsDraft(document: Pick<WarehouseOperationView, "status">) {
  return document.status.trim().toLocaleUpperCase() === "BORRADOR";
}

export function warehouseOperationsCanOpen(
  document: WarehouseOperationView,
  permissions: WarehouseOperationsPanelPermissions | undefined
) {
  const resolved = warehouseOperationsResolvePermissions(permissions);
  return resolved.read && (!warehouseOperationsIsDraft(document) || resolved.edit);
}

export function warehouseOperationsCanDelete(
  document: WarehouseOperationView,
  permissions: WarehouseOperationsPanelPermissions | undefined
) {
  const resolved = warehouseOperationsResolvePermissions(permissions);
  return resolved.read && resolved.delete && warehouseOperationsIsDraft(document);
}

export function warehouseOperationsCounterparty(
  document: WarehouseOperationView,
  mode: WarehouseDocumentMode,
  customers: WarehouseCustomerOption[],
  suppliers: WarehouseSupplierOption[]
) {
  if (mode === "input") {
    const supplier = suppliers.find((candidate) => candidate.id === document.supplierId);
    return document.origin?.trim() || (supplier ? warehouseOperationsSupplierLabel(supplier) : "");
  }
  const customer = customers.find((candidate) => candidate.id === document.customerId);
  return document.destination?.trim() || (customer ? warehouseOperationsCustomerLabel(customer) : "");
}

export function warehouseOperationsWarehouseLabel(
  document: WarehouseOperationView,
  warehouses: WarehouseOption[]
) {
  const warehouse = warehouses.find((candidate) => candidate.id === document.warehouseId);
  return warehouse?.name?.trim() || warehouse?.nombre?.trim() || document.warehouseId;
}

export function warehouseOperationsTotalUnits(document: WarehouseOperationView) {
  return document.lines.reduce((total, line) => {
    const quantity = Number(line.quantity);
    return Number.isFinite(quantity) ? total + quantity : total;
  }, 0);
}

export function warehouseOperationsFilter(
  documents: WarehouseOperationView[],
  options: WarehouseOperationsFilterOptions
) {
  const normalizedQuery = warehouseOperationsNormalize(options.query);
  return documents.filter((document) => {
    if (options.status && document.status !== options.status) {
      return false;
    }
    if (!normalizedQuery) {
      return true;
    }
    const counterparty = warehouseOperationsCounterparty(
      document,
      options.mode,
      options.customers,
      options.suppliers
    );
    const warehouse = warehouseOperationsWarehouseLabel(document, options.warehouses);
    const searchable = [
      document.id,
      document.number,
      document.date,
      document.status,
      document.concept,
      counterparty,
      warehouse,
      document.lines.length,
      warehouseOperationsTotalUnits(document)
    ].join(" ");
    return warehouseOperationsNormalize(searchable).includes(normalizedQuery);
  });
}

export function warehouseOperationsNextId(
  documents: WarehouseOperationView[],
  selectedId: string,
  key: WarehouseOperationsNavigationKey
) {
  if (documents.length === 0) {
    return "";
  }
  if (key === "Home") {
    return documents[0].id;
  }
  if (key === "End") {
    return documents[documents.length - 1].id;
  }

  const backwards = key === "ArrowUp" || key === "ArrowLeft";
  const currentIndex = documents.findIndex((document) => document.id === selectedId);
  if (currentIndex < 0) {
    return backwards ? documents[documents.length - 1].id : documents[0].id;
  }
  const nextIndex = Math.max(
    0,
    Math.min(documents.length - 1, currentIndex + (backwards ? -1 : 1))
  );
  return documents[nextIndex].id;
}

export function WarehouseOperationsPanel({
  mode,
  app = "venta",
  username = "",
  accessToken,
  token,
  products,
  warehouses,
  customers,
  suppliers,
  t,
  locale = "es",
  terminalContext,
  defaultWarehouseId,
  permissions,
  confirmDelete,
  onCreateDocument,
  onOpenDocument,
  onSaved,
  onConfirmed,
  onDeleted,
  onClose,
  onError
}: WarehouseOperationsPanelProps) {
  const resolvedPermissions = warehouseOperationsResolvePermissions(permissions);
  const [documents, setDocuments] = useState<WarehouseOperationView[]>([]);
  const [loading, setLoading] = useState(Boolean(token) && resolvedPermissions.read);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [selectedId, setSelectedId] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogDocument, setDialogDocument] = useState<WarehouseOperationView | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [deleting, setDeleting] = useState(false);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  const toolbarRef = useRef<HTMLDivElement | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);
  const onErrorRef = useRef(onError);

  const labels = warehouseOperationsLabels(t, mode);
  const tableLayout = useTableLayoutPreference({
    app,
    username,
    accessToken,
    tableKey: mode === "input" ? "warehouse.inputs.documents" : "warehouse.outputs.documents",
    definitions: warehouseOperationsColumnDefinitions
  });
  const visibleColumns = visibleTableColumns(tableLayout.layout);
  const columnLabels: Record<WarehouseOperationsColumnKey, string> = {
    number: labels.number,
    date: labels.date,
    counterparty: labels.counterparty,
    warehouse: labels.warehouse,
    status: labels.status,
    lines: labels.lines,
    totalUnits: labels.totalUnits
  };
  const visibleDocuments = useMemo(() => warehouseOperationsFilter(documents, {
    mode,
    query,
    status: statusFilter,
    warehouses,
    customers,
    suppliers
  }), [customers, documents, mode, query, statusFilter, suppliers, warehouses]);
  const statuses = useMemo(
    () => Array.from(new Set(documents.map((document) => document.status).filter(Boolean))).sort(),
    [documents]
  );
  const selectedDocument = documents.find((document) => document.id === selectedId) ?? null;
  const selectedCanOpen = Boolean(
    selectedDocument
    && warehouseOperationsCanOpen(selectedDocument, permissions)
    && (!warehouseOperationsIsDraft(selectedDocument) || token)
  );
  const selectedCanDelete = Boolean(
    selectedDocument
    && token
    && warehouseOperationsCanDelete(selectedDocument, permissions)
  );
  const numberFormatter = useMemo(() => new Intl.NumberFormat(warehouseOperationsLocale(locale), {
    maximumFractionDigits: 3
  }), [locale]);
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat(warehouseOperationsLocale(locale), {
    dateStyle: "short",
    timeZone: "UTC"
  }), [locale]);

  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  useEffect(() => {
    setQuery("");
    setStatusFilter("");
    setSelectedId("");
    setDialogOpen(false);
    setDialogDocument(null);
    setNotice("");
  }, [mode]);

  useEffect(() => {
    let cancelled = false;
    if (!resolvedPermissions.read) {
      setDocuments([]);
      setLoading(false);
      setError(labels.noAccess);
      return;
    }
    if (!token) {
      setDocuments([]);
      setLoading(false);
      setError(labels.noAccess);
      return;
    }

    setLoading(true);
    setError("");
    void warehouseOperationsLoad(mode, token)
      .then((result) => {
        if (!cancelled) {
          setDocuments(result);
          setSelectedId((current) => result.some((document) => document.id === current) ? current : "");
        }
      })
      .catch((requestError) => {
        if (!cancelled) {
          setDocuments([]);
          setError(warehouseOperationsErrorMessage(requestError, labels.loadError));
          warehouseOperationsReportError(requestError, onErrorRef.current);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [labels.loadError, labels.noAccess, mode, reloadKey, resolvedPermissions.read, token]);

  useEffect(() => {
    if (selectedId && !visibleDocuments.some((document) => document.id === selectedId)) {
      setSelectedId("");
    }
  }, [selectedId, visibleDocuments]);

  useEffect(() => {
    if (!dialogOpen) {
      return;
    }
    const mayKeepOpen = dialogDocument
      ? !warehouseOperationsIsDraft(dialogDocument) || resolvedPermissions.edit
      : resolvedPermissions.create;
    if (!mayKeepOpen) {
      setDialogOpen(false);
      setDialogDocument(null);
    }
  }, [dialogDocument, dialogOpen, resolvedPermissions.create, resolvedPermissions.edit]);

  function requestRefresh() {
    setReloadKey((current) => current + 1);
  }

  function reportCallbackError(callbackError: unknown) {
    warehouseOperationsReportError(callbackError, onErrorRef.current);
  }

  function notify(callback: (() => void | Promise<void>) | undefined) {
    try {
      const result = callback?.();
      if (result && typeof result.then === "function") {
        void result.catch(reportCallbackError);
      }
    } catch (callbackError) {
      reportCallbackError(callbackError);
    }
  }

  function notifyDocument<T>(
    callback: ((document: T) => void | Promise<void>) | undefined,
    document: T
  ) {
    try {
      const result = callback?.(document);
      if (result && typeof result.then === "function") {
        void result.catch(reportCallbackError);
      }
    } catch (callbackError) {
      reportCallbackError(callbackError);
    }
  }

  function openCreateDialog() {
    if (!resolvedPermissions.create || !token) {
      return;
    }
    setError("");
    setNotice("");
    setDialogDocument(null);
    setDialogOpen(true);
    notify(onCreateDocument);
  }

  function openDocument(document: WarehouseOperationView) {
    if (!warehouseOperationsCanOpen(document, permissions)) {
      setNotice(labels.noEditPermission);
      return;
    }
    if (warehouseOperationsIsDraft(document) && !token) {
      setNotice(labels.noAccess);
      return;
    }
    setError("");
    setNotice("");
    setSelectedId(document.id);
    setDialogDocument(document);
    setDialogOpen(true);
    notifyDocument(onOpenDocument, document);
  }

  function closeDialog() {
    setDialogOpen(false);
    setDialogDocument(null);
  }

  function handleSaved(document: WarehouseDocumentView) {
    setDialogDocument(document);
    setSelectedId(document.id);
    requestRefresh();
    notifyDocument(onSaved, document);
  }

  function handleConfirmed(document?: WarehouseDocumentView) {
    closeDialog();
    if (document) {
      setSelectedId(document.id);
    }
    requestRefresh();
    notifyDocument(onConfirmed, document);
  }

  async function deleteSelectedDocument() {
    if (!selectedDocument || !token || !selectedCanDelete || deleting) {
      return;
    }
    setError("");
    setNotice("");
    setDeleting(true);
    try {
      const approved = confirmDelete
        ? await confirmDelete(selectedDocument)
        : typeof window !== "undefined" && window.confirm(
          labels.deleteConfirm.replace("{document}", selectedDocument.number || labels.unnumbered)
        );
      if (!approved) {
        return;
      }
      await warehouseOperationsDelete(mode, selectedDocument, token);
      setDocuments((current) => current.filter((document) => document.id !== selectedDocument.id));
      setSelectedId("");
      setNotice(labels.deleted);
      requestRefresh();
      notifyDocument(onDeleted, selectedDocument);
    } catch (deleteError) {
      setError(warehouseOperationsErrorMessage(deleteError, labels.deleteError));
      warehouseOperationsReportError(deleteError, onErrorRef.current);
    } finally {
      setDeleting(false);
    }
  }

  function moveSelection(document: WarehouseOperationView, key: WarehouseOperationsNavigationKey) {
    const nextId = warehouseOperationsNextId(visibleDocuments, document.id, key);
    if (!nextId) {
      return;
    }
    setSelectedId(nextId);
    rowRefs.current.get(nextId)?.focus();
  }

  function handleRowKeyDown(
    event: ReactKeyboardEvent<HTMLTableRowElement>,
    document: WarehouseOperationView
  ) {
    if (event.key === "Enter") {
      event.preventDefault();
      openDocument(document);
      return;
    }
    if (warehouseOperationsIsNavigationKey(event.key)) {
      event.preventDefault();
      moveSelection(document, event.key);
    }
  }

  return (
    <section
      className="stock-sales-history-panel"
      aria-label={labels.title}
      onKeyDown={(event) => {
        if (event.defaultPrevented || event.key !== "Escape" || dialogOpen) {
          return;
        }
        event.preventDefault();
        setQuery("");
        setStatusFilter("");
        setSelectedId("");
        notify(onClose);
      }}
    >
      <div className="stock-history-toolbar" ref={toolbarRef}>
        <label>
          <span>{labels.search}</span>
          <input
            ref={searchRef}
            type="search"
            value={query}
            placeholder={labels.searchPlaceholder}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (enterNavigationIntent(event.key, event) === "next") {
                event.preventDefault();
                toolbarRef.current?.querySelector<HTMLElement>(".erp-select__trigger:not(:disabled)")?.focus();
              }
            }}
          />
        </label>
        <div className="stock-history-filter-field">
          <span>{labels.status}</span>
          <ErpSelect
            aria-label={labels.status}
            value={statusFilter}
            options={[{ value: "", label: labels.all }, ...statuses.map((status) => ({ value: status, label: status }))]}
            onChange={setStatusFilter}
            onCommit={() => toolbarRef.current?.querySelector<HTMLElement>(".warehouse-document-actions button:not(:disabled)")?.focus()}
            onNavigatePrevious={() => searchRef.current?.focus()}
          />
        </div>
        <div className="filter-actions filter-wide warehouse-document-actions">
          {resolvedPermissions.create && (
            <button type="button" disabled={!token || deleting} onClick={openCreateDialog}>
              {labels.create}
            </button>
          )}
          <button
            type="button"
            disabled={!selectedCanOpen || deleting}
            onClick={() => selectedDocument && openDocument(selectedDocument)}
          >
            {selectedDocument && warehouseOperationsIsDraft(selectedDocument) ? labels.edit : labels.view}
          </button>
          {resolvedPermissions.delete && (
            <button
              type="button"
              disabled={!selectedCanDelete || deleting}
              onClick={() => void deleteSelectedDocument()}
            >
              {deleting ? labels.deleting : labels.delete}
            </button>
          )}
        </div>
      </div>

      <div className="stock-history-context">
        <strong>{labels.title}</strong>
        <span>{labels.resultCount.replace("{count}", String(visibleDocuments.length))}</span>
      </div>

      {loading && <p className="stock-operation-status" aria-live="polite">{labels.loading}</p>}
      {error && <p className="stock-operation-status error" role="alert">{error}</p>}
      {notice && <p className="stock-operation-status" aria-live="polite">{notice}</p>}

      <div className="stock-history-table-scroll">
        <table className="report-table warehouse-document-table">
          <colgroup>
            {visibleColumns.map((column) => (
              <col key={column.key} style={{ width: `${column.width}px` }} />
            ))}
          </colgroup>
          <thead>
            <tr>
              {visibleColumns.map((column) => (
                <TableLayoutHeaderCell
                  column={column}
                  key={column.key}
                  resizeLabel={`${t("stock.columns.resize")} ${columnLabels[column.key]}`}
                  onReorder={tableLayout.reorderColumns}
                  onMove={tableLayout.moveColumn}
                  onResize={tableLayout.resizeColumn}
                >
                  {columnLabels[column.key]}
                </TableLayoutHeaderCell>
              ))}
            </tr>
          </thead>
          <tbody>
            {visibleDocuments.map((document) => {
              const counterparty = warehouseOperationsCounterparty(document, mode, customers, suppliers);
              const selected = selectedId === document.id;
              return (
                <tr
                  className={selected ? "selected" : ""}
                  aria-selected={selected}
                  data-operation-id={document.id}
                  key={document.id}
                  ref={(element) => {
                    if (element) {
                      rowRefs.current.set(document.id, element);
                    } else {
                      rowRefs.current.delete(document.id);
                    }
                  }}
                  tabIndex={0}
                  onClick={() => setSelectedId(document.id)}
                  onFocus={() => setSelectedId(document.id)}
                  onDoubleClick={() => openDocument(document)}
                  onKeyDown={(event) => handleRowKeyDown(event, document)}
                >
                  {visibleColumns.map((column) => (
                    <td key={column.key}>
                      {column.key === "number" && (document.number || labels.unnumbered)}
                      {column.key === "date" && warehouseOperationsFormatDate(document.date, dateFormatter)}
                      {column.key === "counterparty" && (counterparty || "-")}
                      {column.key === "warehouse" && (warehouseOperationsWarehouseLabel(document, warehouses) || "-")}
                      {column.key === "status" && document.status}
                      {column.key === "lines" && document.lines.length}
                      {column.key === "totalUnits" && numberFormatter.format(warehouseOperationsTotalUnits(document))}
                    </td>
                  ))}
                </tr>
              );
            })}
            {!loading && !error && visibleDocuments.length === 0 && (
              <tr>
                <td colSpan={visibleColumns.length}>{labels.empty}</td>
              </tr>
            )}
            {!loading && error && visibleDocuments.length === 0 && (
              <tr>
                <td colSpan={visibleColumns.length}>{error}</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <WarehouseDocumentDialog
        mode={mode}
        open={dialogOpen}
        app={app}
        username={username}
        accessToken={accessToken}
        locale={locale}
        token={token}
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={dialogDocument}
        defaultWarehouseId={defaultWarehouseId}
        terminalContext={terminalContext}
        canConfirm={resolvedPermissions.canConfirm}
        onClose={closeDialog}
        onSaved={handleSaved}
        onConfirmed={handleConfirmed}
      />
    </section>
  );
}

export default WarehouseOperationsPanel;

function warehouseOperationsLabels(t: (key: string) => string, mode: WarehouseDocumentMode) {
  return {
    title: warehouseOperationsText(t, mode === "input" ? "stock.nav.inputWarehouse" : "stock.nav.outputWarehouse", mode === "input" ? "Entrada almacen" : "Salida almacen"),
    search: warehouseOperationsText(t, "salesReport.search", "Buscar"),
    searchPlaceholder: warehouseOperationsText(t, "warehouseOperations.searchPlaceholder", "Buscar por numero, contraparte o almacen"),
    status: warehouseOperationsText(t, "salesReport.filter.status", "Estado"),
    all: warehouseOperationsText(t, "salesReport.filter.all", "Todos"),
    create: warehouseOperationsText(t, "warehouseDocument.create", "Crear documento"),
    edit: warehouseOperationsText(t, "warehouseDocument.edit", "Editar borrador"),
    view: warehouseOperationsText(t, "warehouseDocument.view", "Consultar documento"),
    delete: warehouseOperationsText(t, "common.delete", "Eliminar"),
    deleting: warehouseOperationsText(t, "warehouseOperations.deleting", "Eliminando..."),
    number: warehouseOperationsText(t, "warehouseOperations.column.number", "Numero"),
    date: warehouseOperationsText(t, "salesReport.column.date", "Fecha"),
    counterparty: warehouseOperationsText(
      t,
      mode === "input" ? "warehouseDocument.supplier" : "warehouseDocument.customer",
      mode === "input" ? "Proveedor" : "Cliente"
    ),
    warehouse: warehouseOperationsText(t, "stock.column.warehouse", "Almacen"),
    lines: warehouseOperationsText(t, "warehouseOperations.column.lines", "Lineas"),
    totalUnits: warehouseOperationsText(t, "warehouseOperations.column.totalUnits", "Total unidades"),
    resultCount: warehouseOperationsText(t, "warehouseOperations.resultCount", "{count} documentos"),
    loading: warehouseOperationsText(t, "common.loading", "Cargando..."),
    empty: warehouseOperationsText(t, "warehouseOperations.empty", "Sin documentos para la busqueda y estado seleccionados"),
    loadError: warehouseOperationsText(t, "warehouseOperations.loadError", "No se pudieron cargar los documentos de almacen"),
    deleteError: warehouseOperationsText(t, "warehouseOperations.deleteError", "No se pudo eliminar el borrador"),
    deleted: warehouseOperationsText(t, "warehouseOperations.deleted", "Borrador eliminado"),
    deleteConfirm: warehouseOperationsText(t, "warehouseOperations.deleteConfirm", "Eliminar el borrador {document}?"),
    noAccess: warehouseOperationsText(t, "warehouseOperations.noAccess", "Sin acceso a los documentos de almacen"),
    noEditPermission: warehouseOperationsText(t, "warehouseOperations.noEditPermission", "No tiene permiso para editar este borrador"),
    unnumbered: warehouseOperationsText(t, "warehouseOperations.unnumbered", "Sin numero")
  };
}

function warehouseOperationsText(t: (key: string) => string, key: string, fallback: string) {
  const translated = t(key);
  return translated && translated !== key ? translated : fallback;
}

function warehouseOperationsSupplierLabel(supplier: WarehouseSupplierOption) {
  return [
    supplier.legalName ?? supplier.razonSocial,
    supplier.documentNumber ?? supplier.numeroDocumento
  ].filter(Boolean).join(" - ");
}

function warehouseOperationsCustomerLabel(customer: WarehouseCustomerOption) {
  return [
    customer.fiscalName ?? customer.nombreFiscal,
    customer.documentNumber ?? customer.numeroDocumento
  ].filter(Boolean).join(" - ");
}

function warehouseOperationsNormalize(value: unknown) {
  return String(value ?? "")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .trim()
    .toLocaleLowerCase();
}

function warehouseOperationsLocale(locale: LocaleCode) {
  return locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES";
}

function warehouseOperationsFormatDate(value: string, formatter: Intl.DateTimeFormat) {
  const date = new Date(`${value.slice(0, 10)}T00:00:00Z`);
  return Number.isNaN(date.getTime()) ? value : formatter.format(date);
}

export function warehouseOperationsErrorMessage(error: unknown, fallback: string) {
  if (error instanceof TypeError || (error instanceof Error && error.message === "Failed to write request")) {
    return fallback;
  }
  return error instanceof Error && error.message ? error.message : fallback;
}

function warehouseOperationsReportError(error: unknown, callback: ((error: unknown) => void) | undefined) {
  try {
    callback?.(error);
  } catch {
    // A host callback must not replace the operational error shown by the panel.
  }
}

function warehouseOperationsIsNavigationKey(key: string): key is WarehouseOperationsNavigationKey {
  return key === "ArrowDown"
    || key === "ArrowLeft"
    || key === "ArrowRight"
    || key === "ArrowUp"
    || key === "End"
    || key === "Home";
}
