import type { HardwareBridge } from "./hardware/hardware";
import type { LocaleCode, TerminalContext, UserSession } from "./types";

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
      reports?: {
        saveFile: (request: {
          defaultFileName: string;
          filters: Array<{ name: string; extensions: string[] }>;
          bytes: Uint8Array;
        }) => Promise<DesktopResult>;
        exportPdf: (defaultFileName: string) => Promise<DesktopResult>;
        print: () => Promise<DesktopResult>;
      };
      hardware?: HardwareBridge;
    };
  }
}
