import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, TerminalContext } from "../types";
import { frontendVersion } from "../api/runtime";

type TerminalFooterInfoProps = {
  locale: LocaleCode;
  terminalContext: TerminalContext;
  terminalNameOnly?: boolean;
};

export function TerminalFooterInfo({ locale, terminalContext, terminalNameOnly = true }: TerminalFooterInfoProps) {
  const t = createTranslator(locale);
  const terminalName = terminalContext.terminalName?.trim() || terminalContext.terminalCode;

  return <>
    <span>{terminalNameOnly ? terminalName : `${t("login.terminalPrefix")}: ${terminalName}`}</span>
    <span>v{frontendVersion}</span>
  </>;
}
