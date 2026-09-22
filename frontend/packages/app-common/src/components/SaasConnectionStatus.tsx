import { Cloud, CloudSlash } from "@phosphor-icons/react";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

export function SaasConnectionStatus({ locale, connected }: { locale: LocaleCode; connected: boolean }) {
  const t = createTranslator(locale);
  const label = t(connected ? "connection.saasOnline" : "connection.saasOffline");
  const CloudIcon = connected ? Cloud : CloudSlash;

  return (
    <span className={`report-connection ${connected ? "online" : "offline"}`} role="status" aria-label={label} title={label}>
      <CloudIcon size={19} weight="fill" aria-hidden="true" />
      {t("salesReport.connection")}
    </span>
  );
}
