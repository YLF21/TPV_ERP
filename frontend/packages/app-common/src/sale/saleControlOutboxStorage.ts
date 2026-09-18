import { apiBaseUrl } from "../api/runtime";

export type SaleControlContext = { storeId: string; terminalId: string; userId: string };
export type SaleControlLine = { productId: string; code: string; name: string; quantity: number; unitPrice: number };
export type SaleControlEvent = {
  version: 1;
  context: SaleControlContext;
  occurredAt: string;
  saleOperationId: string;
  deletionOperationId: string;
  fullTicketClear: boolean;
  lines: SaleControlLine[];
};
export type SaleControlStorage = {
  list(context: SaleControlContext): Promise<SaleControlEvent[]>;
  put(event: SaleControlEvent): Promise<void>;
  remove(context: SaleControlContext, id: string): Promise<void>;
};
export type SaleControlStorageResult<T = object> = ({ ok: true } & T) | { ok: false; code: string };
export type SaleControlStorageBridge = {
  list(context: SaleControlContext): Promise<SaleControlStorageResult<{ events: SaleControlEvent[] }>>;
  put(event: SaleControlEvent): Promise<SaleControlStorageResult>;
  remove(context: SaleControlContext, id: string): Promise<SaleControlStorageResult>;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function validSaleControlContext(context: unknown): context is SaleControlContext {
  if (!context || typeof context !== "object") return false;
  const value = context as SaleControlContext;
  return [value.storeId, value.terminalId, value.userId].every(id => typeof id === "string" && UUID.test(id));
}
export function sameSaleControlContext(left: SaleControlContext, right: SaleControlContext) {
  return left.storeId === right.storeId && left.terminalId === right.terminalId && left.userId === right.userId;
}
export function validSaleControlEvent(value: unknown): value is SaleControlEvent {
  if (!value || typeof value !== "object") return false;
  const event = value as SaleControlEvent;
  return event.version === 1 && validSaleControlContext(event.context)
    && UUID.test(event.saleOperationId) && UUID.test(event.deletionOperationId)
    && typeof event.occurredAt === "string" && Number.isFinite(Date.parse(event.occurredAt))
    && typeof event.fullTicketClear === "boolean" && Array.isArray(event.lines) && event.lines.length > 0
    && event.lines.every(line => typeof line.productId === "string" && UUID.test(line.productId)
      && typeof line.code === "string" && typeof line.name === "string"
      && typeof line.quantity === "number" && Number.isFinite(line.quantity) && line.quantity !== 0
      && Number.isSafeInteger(Math.round(line.quantity * 1000)) && Math.round(line.quantity * 1000) / 1000 === line.quantity
      && typeof line.unitPrice === "number" && Number.isFinite(line.unitPrice));
}

function requireSuccess<T extends object>(result: SaleControlStorageResult<T>): T {
  if (!result.ok) throw new Error(result.code);
  return result;
}

export function desktopSaleControlStorage(bridge: SaleControlStorageBridge): SaleControlStorage {
  return {
    async list(context) { return requireSuccess(await bridge.list(context)).events; },
    async put(event) { requireSuccess(await bridge.put(event)); },
    async remove(context, id) { requireSuccess(await bridge.remove(context, id)); },
  };
}

export function browserSaleControlStorage(scope = new URL(apiBaseUrl, window.location.href).href): SaleControlStorage {
  const prefix = (context: SaleControlContext) => `${scope}|${context.storeId}|${context.terminalId}|${context.userId}|`;
  const open = () => new Promise<IDBDatabase>((resolve, reject) => {
    if (typeof indexedDB === "undefined") { reject(new Error("CONTROL_STORAGE_UNAVAILABLE")); return; }
    const request = indexedDB.open("tpverp-sale-control", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("events");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("CONTROL_STORAGE_BLOCKED"));
  });
  async function transaction<T>(mode: IDBTransactionMode, action: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const database = await open();
    try {
      return await new Promise<T>((resolve, reject) => {
        const tx = database.transaction("events", mode);
        const request = action(tx.objectStore("events"));
        tx.oncomplete = () => resolve(request.result);
        tx.onabort = tx.onerror = () => reject(tx.error ?? new Error("CONTROL_STORAGE_FAILED"));
      });
    } finally { database.close(); }
  }
  return {
    async list(context) {
      const start = prefix(context);
      const events = await transaction("readonly", store => store.getAll(IDBKeyRange.bound(start, `${start}\uffff`)));
      if (!events.every(event => validSaleControlEvent(event) && sameSaleControlContext(event.context, context))) {
        throw new Error("CONTROL_STORAGE_CORRUPT");
      }
      return events;
    },
    async put(event) {
      if (!validSaleControlEvent(event)) throw new Error("CONTROL_EVENT_INVALID");
      await transaction("readwrite", store => store.add(event, `${prefix(event.context)}${event.deletionOperationId}`));
    },
    async remove(context, id) { await transaction("readwrite", store => store.delete(`${prefix(context)}${id}`)); },
  };
}

export function saleControlStorage(): SaleControlStorage {
  if (window.tpvDesktop) {
    if (!window.tpvDesktop.saleControlOutbox) throw new Error("CONTROL_STORAGE_UNAVAILABLE");
    return desktopSaleControlStorage(window.tpvDesktop.saleControlOutbox);
  }
  return browserSaleControlStorage();
}
