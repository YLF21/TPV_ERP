import { useEffect, useState } from "react";
import { apiBaseUrl } from "../api/runtime";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";

type ScreenContextFooterProps = {
  locale: LocaleCode;
  terminalContext: TerminalContext;
};

function apiServerLabel() {
  if (apiBaseUrl.startsWith("/")) {
    return "local";
  }
  try {
    return new URL(apiBaseUrl).host;
  } catch {
    return apiBaseUrl;
  }
}

function currentOnlineStatus() {
  return typeof navigator === "undefined" ? false : navigator.onLine;
}

export function ScreenContextFooter({ locale, terminalContext }: ScreenContextFooterProps) {
  const t = createTranslator(locale);
  const [saasConnected, setSaasConnected] = useState(currentOnlineStatus);

  useEffect(() => {
    function updateStatus() {
      setSaasConnected(currentOnlineStatus());
    }

    window.addEventListener("online", updateStatus);
    window.addEventListener("offline", updateStatus);
    return () => {
      window.removeEventListener("online", updateStatus);
      window.removeEventListener("offline", updateStatus);
    };
  }, []);

  return (
    <footer className="report-footer-context">
      <span>{terminalContext.storeName}</span>
      <span>{`${t("login.terminalPrefix")}: ${terminalContext.terminalCode}`}</span>
      <span>{`DB: ${apiServerLabel()}`}</span>
      <span className={`report-connection ${saasConnected ? "online" : "offline"}`}>
        <i aria-hidden="true" />
        {t("salesReport.connection")}
      </span>
    </footer>
  );
}
