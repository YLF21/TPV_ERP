import { ApiError, apiRequest, type ApiRequestOptions } from "../api/client";
import {
  sameSaleControlContext, validSaleControlContext, validSaleControlEvent,
  type SaleControlContext, type SaleControlEvent, type SaleControlStorage,
} from "./saleControlOutboxStorage";

export type SaleControlDraft = Pick<SaleControlEvent, "saleOperationId" | "deletionOperationId" | "fullTicketClear" | "lines">;
export type SaleControlDeliveryState = {
  ready: boolean; pending: number; sending: boolean;
  error: "context" | "storage" | "network" | "rejected" | "session" | null;
};
type Request = <T>(path: string, options?: ApiRequestOptions) => Promise<T>;
type Dependencies = {
  token: string; userId?: string; terminalId: string;
  storage: SaleControlStorage; request?: Request; monotonicNow?: () => number;
};

export class SaleControlDelivery {
  private readonly request: Request;
  private readonly monotonicNow: () => number;
  private context?: SaleControlContext;
  private anchor?: { server: number; elapsed: number };
  private state: SaleControlDeliveryState = { ready: false, pending: 0, sending: false, error: null };
  private readonly listeners = new Set<() => void>();
  private active = false;
  private initialization?: Promise<void>;
  private timer?: ReturnType<typeof setTimeout>;
  private controller?: AbortController;
  private running?: Promise<void>;
  private failures = 0;
  private blocked = false;
  private deliveryRequested = false;
  private lastOccurredAt = 0;

  constructor(private readonly dependencies: Dependencies) {
    this.request = dependencies.request ?? apiRequest;
    this.monotonicNow = dependencies.monotonicNow ?? (() => performance.now());
  }
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(change: Partial<SaleControlDeliveryState>) {
    this.state = { ...this.state, ...change };
    this.listeners.forEach(listener => listener());
  }
  start() {
    this.active = true;
    return this.initialize();
  }
  stop() {
    this.active = false;
    if (this.timer) clearTimeout(this.timer);
    this.controller?.abort();
  }
  private async call<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const controller = new AbortController();
    this.controller = controller;
    const timeout = setTimeout(() => controller.abort(), 30_000);
    try {
      return await this.request<T>(path, { ...options, token: this.dependencies.token, signal: controller.signal });
    } finally {
      clearTimeout(timeout);
      if (this.controller === controller) this.controller = undefined;
    }
  }
  private initialize(): Promise<void> {
    if (this.initialization) return this.initialization;
    this.initialization = (async () => {
      try {
        const result = await this.call<SaleControlContext & { serverTime: string }>("/sale-line-deletions/context");
        if (!this.active) return;
        if (!validSaleControlContext(result) || !Number.isFinite(Date.parse(result.serverTime))
            || result.terminalId !== this.dependencies.terminalId
            || (this.dependencies.userId && result.userId !== this.dependencies.userId)) {
          throw new Error("CONTROL_CONTEXT_INVALID");
        }
        this.context = { storeId: result.storeId, terminalId: result.terminalId, userId: result.userId };
        this.anchor = { server: Date.parse(result.serverTime), elapsed: this.monotonicNow() };
        try {
          const events = await this.read();
          if (!this.active) return;
          this.publish({ ready: true, pending: events.length, error: null });
          this.blocked = false;
          void this.flush();
        } catch { this.publish({ ready: false, error: "storage" }); }
      } catch (error) {
        if (this.active) {
          if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
            this.blocked = true;
            this.publish({ ready: false, error: error.status === 401 ? "session" : "rejected" });
          } else if (error instanceof Error && error.message === "CONTROL_CONTEXT_INVALID") {
            this.blocked = true;
            this.publish({ ready: false, error: "context" });
          } else { this.publish({ ready: false, error: "context" }); this.schedule(); }
        }
      }
    })().finally(() => { this.initialization = undefined; });
    return this.initialization;
  }
  private async read() {
    if (!this.context) throw new Error("CONTROL_CONTEXT_UNAVAILABLE");
    const events = await this.dependencies.storage.list(this.context);
    if (!events.every(event => validSaleControlEvent(event) && sameSaleControlContext(event.context, this.context!))) {
      throw new Error("CONTROL_STORAGE_CORRUPT");
    }
    return events.sort((a, b) => a.occurredAt.localeCompare(b.occurredAt)
      || a.deletionOperationId.localeCompare(b.deletionOperationId));
  }
  async persist(draft: SaleControlDraft) {
    if (!this.active || !this.context || !this.anchor || !this.state.ready) throw new Error("CONTROL_NOT_READY");
    const occurredAt = Math.max(this.lastOccurredAt + 1,
      this.anchor.server + Math.max(0, this.monotonicNow() - this.anchor.elapsed));
    const event: SaleControlEvent = {
      version: 1, ...draft, context: { ...this.context },
      occurredAt: new Date(occurredAt).toISOString(),
      lines: draft.lines.map(line => ({ ...line })),
    };
    try {
      if (!validSaleControlEvent(event)) throw new Error("CONTROL_EVENT_INVALID");
      await this.dependencies.storage.put(event);
      this.lastOccurredAt = occurredAt;
    } catch (error) {
      this.publish({ error: "storage" });
      throw error;
    }
    this.publish({ pending: this.state.pending + 1 });
    return event;
  }
  // Called after the cart mutation. Persisting alone never implies server delivery.
  deliver() { this.deliveryRequested = true; void this.flush(); }
  retry = () => {
    if (!this.active) return;
    if (this.timer) clearTimeout(this.timer);
    this.blocked = false;
    this.failures = 0;
    if (!this.state.ready) void this.initialize();
    else void this.flush();
  };
  online = () => {
    if (!this.blocked) this.retry();
  };
  private schedule() {
    if (!this.active || this.blocked) return;
    if (this.timer) clearTimeout(this.timer);
    this.failures += 1;
    this.timer = setTimeout(() => {
      if (this.state.ready) void this.flush(); else void this.initialize();
    }, Math.min(60_000, 2_000 * 2 ** Math.min(5, this.failures - 1)));
  }
  private flush(): Promise<void> {
    if (this.running) return this.running;
    if (!this.active || !this.state.ready || this.blocked) return Promise.resolve();
    this.deliveryRequested = false;
    this.running = (async () => {
      this.publish({ sending: true });
      try {
        while (this.active && !this.blocked) {
          let events: SaleControlEvent[];
          try { events = await this.read(); }
          catch { this.blocked = true; this.publish({ error: "storage" }); return; }
          this.publish({ pending: events.length });
          if (!events.length) { this.publish({ error: null }); break; }
          // Drain the snapshot before listing again: desktop listing reads files in the main process.
          for (const event of events) {
            if (!this.active) return;
            try {
              const { version: _version, ...body } = event;
              await this.call("/sale-line-deletions", { body });
            } catch (error) {
              if (!this.active) return;
              if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
                this.blocked = true;
                this.publish({ error: error.status === 401 ? "session" : "rejected" });
              } else { this.publish({ error: "network" }); this.schedule(); }
              return;
            }
            if (!this.active) return;
            try { await this.dependencies.storage.remove(event.context, event.deletionOperationId); }
            catch { this.blocked = true; this.publish({ error: "storage" }); return; }
            this.publish({ pending: Math.max(0, this.state.pending - 1) });
            this.failures = 0;
          }
        }
      } finally { this.publish({ sending: false }); }
    })().finally(() => {
      this.running = undefined;
      if (this.deliveryRequested && this.active && !this.blocked && !this.state.error) void this.flush();
    });
    return this.running;
  }
}
