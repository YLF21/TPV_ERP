import { Cloud, CloudSlash, Database } from "@phosphor-icons/react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import { useScreenConnectionStatus } from "./useScreenConnectionStatus";

type ScreenContextFooterProps = {
  locale: LocaleCode;
  terminalContext: TerminalContext;
};

export function ScreenContextFooter({ locale, terminalContext }: ScreenContextFooterProps) {
  const t = createTranslator(locale);
  const { backendLabel, saasConnected } = useScreenConnectionStatus();
  const connectionLabel = t(saasConnected ? "connection.saasOnline" : "connection.saasOffline");
  const CloudIcon = saasConnected ? Cloud : CloudSlash;

  return (
    <footer className="report-footer-context">
      <span>{terminalContext.storeName}</span>
      <span>{`${t("login.terminalPrefix")}: ${terminalContext.terminalCode}`}</span>
      <span className="report-database" title={t("connection.backendAddress")}>
        <Database size={17} weight="bold" aria-hidden="true" />
        {`DB: ${backendLabel ?? "—"}`}
      </span>
      <span className={`report-connection ${saasConnected ? "online" : "offline"}`} role="status" aria-label={connectionLabel} title={connectionLabel}>
        <CloudIcon size={19} weight="fill" aria-hidden="true" />
        {t("salesReport.connection")}
      </span>
    </footer>
  );
}
