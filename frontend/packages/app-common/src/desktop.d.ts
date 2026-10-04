import type { HardwareBridge } from "./hardware/hardware";
import type { LocaleCode, TerminalContext, UserSession } from "./types";
import type { SaleOperationAuthorization } from "./sale/operationSecurity";
import type { SaleInterfaceMode } from "./components/saleInterfacePreferences";
import type { SaleControlStorageBridge } from "./sale/saleControlOutboxStorage";

type DesktopResult = { ok: true; canceled?: boolean; filePath?: string } | { ok: false; code: string; message: string };

type TerminalLinkState = {
  requestId: string; bindingId: string; terminalId: string; terminalCode: string;
  terminalName: string; storeId: string; storeName: string; installationId: string;
  status: "PENDING" | "ACTIVE" | "DISABLED" | "RELEASED" | "CANCELLED" | "EXPIRED";
  expiresAt?: string;
};
type TerminalBootstrap = {
  protocolVersion: number; installationId: string; installationReference: string;
  storeId: string; storeName: string; publicKey: string; challenge: string;
  signature: string; maxWindows: number;
  slots: Array<{ code: string; status: string; name?: string; terminalId?: string;
    bindingId?: string; expiresAt?: string; outOfQuota?: boolean }>;
};
type BackendConnectionResult<T> = ({ ok: true } & T) | { ok: false; code: string; message: string };

declare global {
  interface Window {
    tpvDesktop?: {
      backendConnection?: {
        load: () => Promise<BackendConnectionResult<{ configuration?: { backendUrl: string; installationId?: string };
          link?: TerminalLinkState; identity?: TerminalContext | null; deviceName: string; configurationError?: string;
          restartRequired?: boolean; pendingRequest?: { code: string; name: string; backendUrl: string;
            mode: "SERVER_ADMIN" | "SERVER_EXISTING" | "WORKSTATION" | "LEGACY_POS" };
          linkedIdentity?: { installationId: string; bindingId: string; terminalId: string; terminalCode: string; terminalName?: string; storeName: string };
          legacyIdentity?: { terminalId: string; terminalCode: string; storeName: string } }>>;
        discover: () => Promise<BackendConnectionResult<{ servers: Array<{ backendUrl: string; label: string }> }>>;
        probe: (request: { backendUrl: string }) => Promise<BackendConnectionResult<{ server: TerminalBootstrap; sameInstallation: boolean; localServer: boolean }>>;
        requestLink: (request: { backendUrl: string; code: string; name: string;
          administrator?: { username: string; password: string } }) => Promise<BackendConnectionResult<{ link: TerminalLinkState;
          identity?: TerminalContext; restartRequired?: boolean }>>;
        refreshLink: () => Promise<BackendConnectionResult<{ link: TerminalLinkState; identity?: TerminalContext;
          restartRequired?: boolean }>>;
        cancelLink: () => Promise<BackendConnectionResult<{ link: TerminalLinkState | null; localOnly?: boolean }>>;
        saveAddress: (request: { backendUrl: string }) => Promise<BackendConnectionResult<{ restartRequired: boolean }>>;
        restart: () => Promise<BackendConnectionResult<Record<string, never>>>;
      };
      saleControlOutbox?: SaleControlStorageBridge;
      closeApplication: () => Promise<void>;
      terminalIdentity?: {
        load: () => Promise<DesktopResult & { identity?: TerminalContext | null }>;
        save: (identity: TerminalContext) => Promise<DesktopResult>;
      };
      salesDocuments?: {
        open: (bootstrap: {
          locale: LocaleCode;
          session: UserSession;
          terminalContext: TerminalContext;
          interfaceMode: SaleInterfaceMode;
        }) => Promise<DesktopResult & { focused?: boolean }>;
        consumeBootstrap: () => Promise<{
          locale: LocaleCode;
          session: UserSession;
          terminalContext: TerminalContext;
          interfaceMode: SaleInterfaceMode;
        } | null>;
        close: () => Promise<DesktopResult>;
      };
      salesUtilities?: {
        open: (bootstrap: {
          kind: "INTERNAL_EAN" | "PRODUCT_LABEL";
          locale: LocaleCode;
          session: UserSession;
          terminalContext: TerminalContext;
          interfaceMode?: SaleInterfaceMode;
          initialProductId?: string;
          authorization?: SaleOperationAuthorization;
        }) => Promise<DesktopResult & {
          catalogChanged?: boolean;
          printed?: boolean;
          pdf?: boolean;
        }>;
        consumeBootstrap: () => Promise<{
          kind: "INTERNAL_EAN" | "PRODUCT_LABEL";
          locale: LocaleCode;
          session: UserSession;
          terminalContext: TerminalContext;
          interfaceMode?: SaleInterfaceMode;
          initialProductId?: string;
          authorization?: SaleOperationAuthorization;
        } | null>;
        complete: (result?: {
          catalogChanged?: boolean;
          printed?: boolean;
          pdf?: boolean;
        }) => Promise<DesktopResult>;
        close: () => Promise<DesktopResult>;
      };
      reports?: {
        saveFile: (request: {
          defaultFileName: string;
          filters: Array<{ name: string; extensions: string[] }>;
          bytes: Uint8Array;
        }) => Promise<DesktopResult>;
        exportPdf: (defaultFileName: string) => Promise<DesktopResult>;
        exportTablePdf: (request: {
          title: string;
          subject: string;
          code?: string;
          imageDataUrl?: string;
          imageFallback?: string;
          filters: Array<{ label: string; value: string }>;
          columns: Array<{ key: string; label: string }>;
          rows: string[][];
          totals: Array<{ label: string; value: string }>;
        }, defaultFileName: string) => Promise<DesktopResult>;
        print: () => Promise<DesktopResult>;
      };
      hardware?: HardwareBridge;
    };
  }
}
