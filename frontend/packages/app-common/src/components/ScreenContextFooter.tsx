import { Database } from "@phosphor-icons/react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import { useScreenConnectionStatus } from "./useScreenConnectionStatus";
import { SaasConnectionStatus } from "./SaasConnectionStatus";

type ScreenContextFooterProps = {
  locale: LocaleCode;
  terminalContext: TerminalContext;
  terminalNameOnly?: boolean;
};

export function ScreenContextFooter({ locale, terminalContext, terminalNameOnly = false }: ScreenContextFooterProps) {
  const t = createTranslator(locale);
  const { backendLabel, saasConnected } = useScreenConnectionStatus();

  return (
    <footer className="report-footer-context">
      <span>{terminalContext.storeName}</span>
      <span>{terminalNameOnly ? terminalContext.terminalName || terminalContext.terminalCode : `${t("login.terminalPrefix")}: ${terminalContext.terminalCode}`}</span>
      <span className="report-database" title={t("connection.backendAddress")}>
        <Database size={17} weight="bold" aria-hidden="true" />
        {`DB: ${backendLabel ?? "—"}`}
      </span>
      <SaasConnectionStatus locale={locale} connected={saasConnected} />
    </footer>
  );
}
