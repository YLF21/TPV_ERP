import { useState } from "react";
import type { SmtpSecurity } from "../../lib/mail-api";
import { useSmtpProviderCopy } from "./smtp-provider-copy";

export const smtpProviders = [
  { id: "gmail", label: "Gmail / Google Workspace", host: "smtp.gmail.com", port: "587", security: "STARTTLS", hint: "gmailPasswordHint", name: "Google", url: "https://myaccount.google.com/apppasswords" },
  { id: "yahoo", label: "Yahoo Mail", host: "smtp.mail.yahoo.com", port: "465", security: "SSL", hint: "appPasswordHint", name: "Yahoo", url: "https://login.yahoo.com/account/security" },
  { id: "qq", label: "QQ Mail", host: "smtp.qq.com", port: "465", security: "SSL", hint: "authorizationCodeHint", name: "QQ", url: "https://mail.qq.com/" },
  { id: "icloud", label: "iCloud Mail", host: "smtp.mail.me.com", port: "587", security: "STARTTLS", hint: "appPasswordHint", name: "Apple", url: "https://account.apple.com/" },
  { id: "netease163", label: "NetEase 163", host: "smtp.163.com", port: "465", security: "SSL", hint: "authorizationCodeHint", name: "163", url: "https://mail.163.com/" },
  { id: "exmail", label: "Tencent Exmail", host: "smtp.exmail.qq.com", port: "465", security: "SSL", hint: "smtpHint", name: "Exmail", url: "https://exmail.qq.com/login" },
] as const satisfies readonly { security: SmtpSecurity; [key: string]: string }[];

export type SmtpProviderId = typeof smtpProviders[number]["id"] | "custom";

export function recognizeSmtpProvider(host: string | null): SmtpProviderId {
  return smtpProviders.find(provider => provider.host === host?.trim().toLowerCase())?.id ?? "custom";
}

export function SmtpProviderHelp({ providerId }: { providerId: SmtpProviderId }) {
  const copy = useSmtpProviderCopy();
  const provider = smtpProviders.find(item => item.id === providerId);
  const [open, setOpen] = useState(false);
  const title = copy("passwordBrowserHint");

  return <div className="smtp-help smtp-wide">
    <p className="module-help">{copy(provider?.hint ?? "smtpHint")}</p>
    {provider && <a className="smtp-help-link" href={provider.url} target="_blank" rel="noopener noreferrer"
      title={title} onClick={() => setOpen(true)}>{copy("openPasswordProvider").replace("{provider}", provider.name)} <span aria-hidden="true">↗</span></a>}
    <details open={open} onToggle={event => setOpen(event.currentTarget.open)}>
      <summary>{copy("passwordStepsTitle")}</summary>
      <ol>
        {([1, 2, 3] as const).map(step => <li key={step}>
          {copy(`passwordSteps.${providerId}.${step}`)}
          {step === 1 && provider && <> <a href={provider.url} target="_blank" rel="noopener noreferrer" title={title}>{provider.url}</a></>}
        </li>)}
        <li>{copy("passwordStepsFinish")}</li>
      </ol>
      <p className="module-help">{copy(providerId === "gmail" ? "googlePasswordUnavailable" : "passwordOptionsUnavailable")}</p>
    </details>
  </div>;
}
