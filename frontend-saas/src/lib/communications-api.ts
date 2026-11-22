import { inboxRequest } from "./api";
import type { Credentials } from "./types";

export const COMMUNICATION_PAGE_SIZE = 25;
export const COMMUNICATION_SUBJECT_LIMIT = 200;
export const COMMUNICATION_HTML_LIMIT = 100_000;
export const COMMUNICATION_RECIPIENT_LIMIT = 10_000;

export type AudienceMode = "ALL" | "SELECTED";
export type CommunicationStatus = "DRAFT" | "QUEUED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
export type DeliveryStatus = "REVIEW" | "PENDING" | "SENDING" | "SENT" | "FAILED" | "UNCERTAIN" | "CANCELLED";
export type DeliveryCounts = Record<"pending" | "sending" | "sent" | "failed" | "uncertain" | "cancelled", number>;
export type CommunicationDraft = { subject: string; html: string; audienceMode: AudienceMode; recipientIds: string[]; senderAccountId: string | null };
export type CommunicationContent = { html: string; text: string };
export type CommunicationSummary = {
  id: string;
  subject: string;
  status: CommunicationStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  counts: DeliveryCounts;
};
export type CommunicationDetail = CommunicationSummary & CommunicationDraft & CommunicationContent & {
  fromAddress: string | null; fromName: string | null; replyTo: string | null;
};
export type CommunicationReview = CommunicationContent & {
  reviewId: string;
  version: number;
  subject: string;
  recipientCount: number;
  omittedCount: number;
  duplicateCount: number;
  smtpReady: boolean;
  fromAddress: string;
  fromName: string;
  replyTo: string;
  smtpVersion: number;
  senderAccountId: string;
};
export type AudienceEntry = {
  id: string;
  label: string;
  email: string | null;
  eligible: boolean;
  reason: "INACTIVE" | "MISSING_EMAIL" | "INVALID_EMAIL" | null;
  companyNames: string[];
  sources: ("CLIENT_USER" | "COMPANY_CONTACT" | "COMPANY_OWNER")[];
};
export type CommunicationRecipient = {
  id: string;
  label: string;
  email: string;
  status: DeliveryStatus;
  attempts: number;
  lastError: string | null;
  sentAt: string | null;
};
type Page<T> = { items: T[]; total: number };

const root = "/api/v1/admin/communications";
const communicationPath = (id: string) => `${root}/${encodeURIComponent(id)}`;

function pageQuery(page: number, extra: Record<string, string> = {}) {
  return new URLSearchParams({ page: String(page), size: String(COMMUNICATION_PAGE_SIZE), ...extra });
}

export const communicationsApi = {
  list: (credentials: Credentials, page: number) =>
    inboxRequest<Page<CommunicationSummary>>(credentials, `${root}?${pageQuery(page)}`),
  detail: (credentials: Credentials, id: string) =>
    inboxRequest<CommunicationDetail>(credentials, communicationPath(id)),
  create: (credentials: Credentials, body: CommunicationDraft) =>
    inboxRequest<CommunicationDetail>(credentials, root, { method: "POST", body }),
  update: (credentials: Credentials, id: string, body: CommunicationDraft & { version: number }) =>
    inboxRequest<CommunicationDetail>(credentials, communicationPath(id), { method: "PUT", body }),
  audience: (credentials: Credentials, page: number, q: string) =>
    inboxRequest<Page<AudienceEntry>>(credentials, `${root}/audience?${pageQuery(page, { q })}`),
  preview: (credentials: Credentials, html: string) =>
    inboxRequest<CommunicationContent>(credentials, `${root}/preview`, { method: "POST", body: { html } }),
  review: (credentials: Credentials, id: string, version: number) =>
    inboxRequest<CommunicationReview>(credentials, `${communicationPath(id)}/review`, { method: "POST", body: { version } }),
  recipients: (credentials: Credentials, id: string, page: number, reviewId?: string) =>
    inboxRequest<Page<CommunicationRecipient>>(credentials, `${communicationPath(id)}/recipients?${pageQuery(page, reviewId ? { reviewId } : {})}`),
  send: (credentials: Credentials, id: string, body: { version: number; reviewId: string; recipientCount: number }) =>
    inboxRequest<CommunicationDetail>(credentials, `${communicationPath(id)}/send`, { method: "POST", body }),
  test: (credentials: Credentials, id: string, version: number, recipient: string) =>
    inboxRequest<void>(credentials, `${communicationPath(id)}/test`, { method: "POST", body: { version, recipient } }),
  cancel: (credentials: Credentials, id: string, version: number) =>
    inboxRequest<CommunicationDetail>(credentials, `${communicationPath(id)}/cancel`, { method: "POST", body: { version } }),
  retry: (credentials: Credentials, id: string, recipientId: string, attempts: number, confirmUncertain: boolean) =>
    inboxRequest<CommunicationDetail>(credentials, `${communicationPath(id)}/recipients/${encodeURIComponent(recipientId)}/retry`, {
      method: "POST", body: { attempts, confirmUncertain },
    }),
};
