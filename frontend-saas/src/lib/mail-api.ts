import { ApiError, inboxRequest } from "./api";
import type { Credentials } from "./types";

export type SmtpSecurity = "STARTTLS" | "SSL";
export type MailOAuthProvider = "GOOGLE" | "MICROSOFT";

export type SmtpSettings = {
  enabled: boolean;
  host: string | null;
  port: number;
  security: SmtpSecurity;
  username: string | null;
  hasPassword: boolean;
  fromAddress: string | null;
  fromName: string | null;
  replyTo: string | null;
  portalUrl: string | null;
  version: number;
  cryptoReady: boolean;
  configured: boolean;
  readonly testReady: boolean;
  oauthProvider: MailOAuthProvider | null;
};

export type SmtpUpdate = Omit<SmtpSettings, "hasPassword" | "cryptoReady" | "configured" | "testReady" | "oauthProvider"> & {
  password?: string;
  clearPassword?: boolean;
};

export type SmtpAccount = SmtpSettings & { id: string; defaultAccount: boolean };

export type MailOAuthStatus = {
  providers: { provider: MailOAuthProvider; available: boolean }[];
  connection: { provider: MailOAuthProvider; email: string } | null;
};

type MailOAuthConnect = {
  accountId?: string;
  provider: MailOAuthProvider;
  email: string;
  version: number;
  fromName: string;
  replyTo: string;
  portalUrl: string;
  enabled: boolean;
};

export type RequestEmailDelivery = {
  status: "PENDING" | "PROCESSING" | "DELIVERED" | "FAILED";
  attempts: number;
  lastError: string | null;
  nextAttemptAt: string | null;
  deliveredAt: string | null;
};

const settingsPath = "/api/v1/admin/smtp-settings";
const accountsPath = `${settingsPath}/accounts`;
const accountPath = (id: string) => `${accountsPath}/${encodeURIComponent(id)}`;
const oauthPath = "/api/v1/admin/mail-oauth";

export const mailApi = {
  settings: (credentials: Credentials) =>
    inboxRequest<SmtpSettings & { id: string | null }>(credentials, settingsPath),
  saveNotices: (credentials: Credentials, body: {
    accountId: string; enabled: boolean; portalUrl: string; version: number;
    defaultVersion: number; defaultAccountId: string | null; confirmPending?: boolean;
  }) => inboxRequest<SmtpAccount>(credentials, `${settingsPath}/notices`, { method: "PUT", body }),
  accounts: (credentials: Credentials) =>
    inboxRequest<SmtpAccount[]>(credentials, accountsPath),
  addAccount: (credentials: Credentials, body: SmtpUpdate) =>
    inboxRequest<SmtpAccount>(credentials, accountsPath, { method: "POST", body }),
  updateAccount: (credentials: Credentials, id: string, body: SmtpUpdate) =>
    inboxRequest<SmtpAccount>(credentials, accountPath(id), { method: "PUT", body }),
  deleteAccount: (credentials: Credentials, id: string, version: number) =>
    inboxRequest<void>(credentials, `${accountPath(id)}?version=${version}`, { method: "DELETE" }),
  test: (credentials: Credentials, id: string, recipient: string) =>
    inboxRequest<void>(credentials, `${accountPath(id)}/test`, { method: "POST", body: { recipient } }),
  oauthStatus: (credentials: Credentials, id?: string) =>
    inboxRequest<MailOAuthStatus>(credentials, `${oauthPath}/status${id ? `?accountId=${encodeURIComponent(id)}` : ""}`),
  connect: (credentials: Credentials, body: MailOAuthConnect) =>
    inboxRequest<{ authorizationUrl: string }>(credentials, `${oauthPath}/connect`, { method: "POST", body }),
  disconnect: (credentials: Credentials, id: string, version: number) =>
    inboxRequest<void>(credentials, `${oauthPath}/connection?accountId=${encodeURIComponent(id)}&version=${version}`, { method: "DELETE" }),
};

export function mailErrorCode(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined;
  try {
    const code: unknown = JSON.parse(error.message)?.code;
    return typeof code === "string" ? code : undefined;
  } catch {
    return undefined;
  }
}
