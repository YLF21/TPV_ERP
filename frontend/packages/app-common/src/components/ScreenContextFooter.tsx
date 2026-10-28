import { Database } from "@phosphor-icons/react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import { useScreenConnectionStatus } from "./useScreenConnectionStatus";
import { SaasConnectionStatus } from "./SaasConnectionStatus";
import { validStoreInternalCode } from "../storeInternalCode";
import { TerminalFooterInfo } from "./TerminalFooterInfo";

type ScreenContextFooterProps = {
  locale: LocaleCode;
  terminalContext: TerminalContext;
  terminalNameOnly?: boolean;
};

export function ScreenContextFooter({ locale, terminalContext, terminalNameOnly = true }: ScreenContextFooterProps) {
  const t = createTranslator(locale);
  const { backendLabel, saasConnected } = useScreenConnectionStatus();
  const storeCode = validStoreInternalCode(terminalContext.storeInternalCode);

  return (
    <footer className="report-footer-context">
      <span title={storeCode ? t("store.internalCodeTitle") : undefined}>
        {terminalContext.storeName}{storeCode ? ` · ${storeCode}` : ""}
      </span>
      <TerminalFooterInfo locale={locale} terminalContext={terminalContext} terminalNameOnly={terminalNameOnly} />
      <span className="report-database" title={t("connection.backendAddress")}>
        <Database size={17} weight="bold" aria-hidden="true" />
        {`DB: ${backendLabel ?? "—"}`}
      </span>
      <SaasConnectionStatus locale={locale} connected={saasConnected} />
    </footer>
  );
}
