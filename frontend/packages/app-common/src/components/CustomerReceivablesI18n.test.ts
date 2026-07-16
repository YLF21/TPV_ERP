import { describe, expect, it } from "vitest";
import { MessagesEn } from "../i18n/MessagesEn";
import { MessagesEs } from "../i18n/MessagesEs";
import { MessagesZh } from "../i18n/MessagesZh";

const visibleKeys = [
  "receivables.title",
  "receivables.subtitle",
  "receivables.search",
  "receivables.status",
  "receivables.documentType",
  "receivables.overdueOnly",
  "receivables.dueFrom",
  "receivables.dueTo",
  "receivables.action.collect",
  "receivables.payment.title",
  "receivables.payment.pendingBalance",
  "receivables.payment.amount",
  "receivables.payment.transferReference",
  "pendingSale.title",
  "pendingSale.documentType",
  "pendingSale.dueDate",
  "pendingSale.total",
  "pendingSale.paid",
  "pendingSale.pending",
  "pendingSale.confirm",
  "salesReport.daily.invoiced",
  "salesReport.daily.collectedCurrent",
  "salesReport.daily.newPending",
  "salesReport.daily.priorDebtCollected",
  "salesReport.daily.cashInflow"
] as const;

describe("customer receivables translations", () => {
  it.each([
    ["es", MessagesEs.values],
    ["en", MessagesEn.values],
    ["zh", MessagesZh.values]
  ])("contains translated visible copy in %s without mojibake", (_locale, values) => {
    for (const key of visibleKeys) {
      expect(values[key], key).toBeTruthy();
      expect(values[key], key).not.toBe(key);
      expect(values[key], key).not.toMatch(/[璺鑴甯椹帽贸铆]/);
    }
  });
});
