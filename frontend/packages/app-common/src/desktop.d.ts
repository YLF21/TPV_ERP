import type { HardwareBridge } from "./hardware/hardware";
import type { LocaleCode, TerminalContext, UserSession } from "./types";
import type { SaleOperationAuthorization } from "./sale/operationSecurity";

type DesktopResult = { ok: true; canceled?: boolean; filePath?: string } | { ok: false; code: string; message: string };

declare global {
  interface Window {
    tpvDesktop?: {
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
        }) => Promise<DesktopResult & { focused?: boolean }>;
        consumeBootstrap: () => Promise<{
          locale: LocaleCode;
          session: UserSession;
          terminalContext: TerminalContext;
        } | null>;
        close: () => Promise<DesktopResult>;
      };
      salesUtilities?: {
        open: (bootstrap: {
          kind: "INTERNAL_EAN" | "PRODUCT_LABEL";
          locale: LocaleCode;
          session: UserSession;
          terminalContext: TerminalContext;
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
