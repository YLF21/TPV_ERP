import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { getApplicationGuides } from "../src/applicationGuides.ts";
import { getMarketingContent } from "../src/marketingI18n.ts";
import { getTourExamples } from "../src/tourExamples.ts";

// Authored copy and checked-in implementation evidence only. These checks do
// not run the sales app, access a database, open a browser or listen on HTTP.
const expectations = {
  es: {
    customer: /cliente/i,
    authorisation: /autorizaci[oó]n|autorizaciones/i,
    documents: /facturas y albaranes de venta/i,
    authorisedTicket: /cobro unificado tambi[eé]n permite tickets pendientes/i,
    notCollected: /saldo pendiente no cuenta como dinero cobrado/i,
    partialExample: /total 2,50.*1,00.*1,50.*antes de finalizar/i,
    noAutomaticDebt: /no es deuda automática/i,
    fullWorkflow: /cobra el total/i,
    distinguish: /distingue el dinero cobrado.*pendiente/i
  },
  en: {
    customer: /customer/i,
    authorisation: /authorisations?/i,
    documents: /sales invoices and delivery notes/i,
    authorisedTicket: /unified checkout also allows pending receipts/i,
    notCollected: /outstanding amount is not money collected/i,
    partialExample: /total 2\.50.*1\.00.*1\.50.*before completion/i,
    noAutomaticDebt: /no debt is created automatically/i,
    fullWorkflow: /collect the full amount/i,
    distinguish: /distinguish money collected.*still owes/i
  },
  zh: {
    customer: /客户/,
    authorisation: /授权/,
    documents: /销售发票和送货单/,
    authorisedTicket: /统一收款流程也支持待收款小票/,
    notCollected: /待收金额不计为已收款/,
    partialExample: /总额 2\.50.*1\.00.*完成销售前.*1\.50/,
    noAutomaticDebt: /不会自动成为客户欠款/,
    fullWorkflow: /收取全额/,
    distinguish: /区分实际收到的款项.*尚未支付/
  }
};

for (const [language, expected] of Object.entries(expectations)) {
  test(`${language}: distinguish a checkout remainder from authorised customer receivables`, () => {
    const marketing = getMarketingContent(language);
    const salesGuide = getApplicationGuides(language).apps.venta;
    const paymentStep = getTourExamples(language).stepDetails[0][0];
    const answer = salesGuide.questions[1].answer;

    for (const field of ["documents", "authorisedTicket", "customer", "authorisation", "notCollected"]) {
      assert.match(answer, expected[field], `${language}: receivables FAQ must explain ${field}`);
    }
    assert.match(paymentStep.example, expected.partialExample, "The example matches the captured 2.50 total, 1.00 entered cash and 1.50 remainder before completion");
    assert.match(paymentStep.example, expected.noAutomaticDebt, "An amount left during checkout is not automatically a customer receivable");
    assert.match(paymentStep.example, expected.customer);
    assert.match(paymentStep.example, expected.authorisation);
    assert.doesNotMatch(paymentStep.example, /cobrado completo|fully paid|全额付清/i);
    assert.match(salesGuide.workflow[1].body, expected.fullWorkflow);
    assert.match(paymentStep.benefit, expected.distinguish);

    for (const text of [marketing.features.groups[0][3][0], marketing.apps.items[0][2], paymentStep.actions[2]]) {
      assert.match(text, expected.customer, "Visible short summaries retain the customer requirement");
      assert.match(text, expected.authorisation, "Visible short summaries retain the authorisation requirement");
    }
    // No invented payment terms, amounts, deadlines or automatic approval.
    const revisedCopy = [answer, paymentStep.example, paymentStep.benefit, ...paymentStep.actions].join(" ");
    assert.doesNotMatch(revisedCopy, /\b\d+\s*(?:d[ií]as?|days?)\b|\d+\s*天|sin autorizaci[oó]n|without authorisation|无需授权/i);
    assert.doesNotMatch(revisedCopy, /(?:\d+[,.]?\d*\s*(?:€|EUR|USD)|[$€]\s*\d)/i);
  });
}

test("receivables clarification reuses existing compact copy slots", () => {
  for (const language of Object.keys(expectations)) {
    const marketing = getMarketingContent(language);
    const guide = getApplicationGuides(language).apps.venta;
    assert.equal(marketing.features.groups.length, 4);
    assert.equal(marketing.features.groups[0][3].length, 3);
    assert.equal(marketing.apps.items.length, 4);
    assert.equal(getTourExamples(language).stepDetails[0].length, 3);
    assert.equal(getTourExamples(language).stepDetails[0][0].actions.length, 3);
    assert.equal(guide.tasks.length, 3);
    assert.equal(guide.workflow.length, 3);
    assert.equal(guide.questions.length, 2);
    assert.ok(guide.questions[1].answer.length <= 240, `${language}: keep the clarification concise`);
    assert.ok(marketing.apps.items[0][2].length <= 125, `${language}: preserve compact application summary`);
  }
});

test("the two pending-sale paths are backed by current implementation, not an outdated universal restriction", async () => {
  const [documentDialog, checkout, sessions, documents, receivables] = await Promise.all([
    "../../frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx",
    "../../frontend/packages/app-common/src/components/PaymentAllocationPanel.tsx",
    "../../backend/src/main/java/com/tpverp/backend/document/SalePaymentSessionService.java",
    "../../backend/src/main/java/com/tpverp/backend/document/DocumentService.java",
    "../../backend/src/main/java/com/tpverp/backend/document/CommercialDocument.java"
  ].map(path => readFile(new URL(path, import.meta.url), "utf8")));
  assert.match(documentDialog, /<option value="ALBARAN_VENTA">/);
  assert.match(documentDialog, /<option value="FACTURA_VENTA">/);
  assert.match(checkout, /method === "PENDING" && !customerSelected/);
  assert.match(sessions, /authorizePendingTicket\(/);
  assert.match(sessions, /kind == SalePaymentAllocationKind\.PENDING/);
  assert.match(sessions, /pendingAmount\.signum\(\) > 0\s*\? documents\.createPendingTicketFromSnapshot/);
  assert.match(documents, /CommercialDocument createPendingTicketFromSnapshot\(/);
  assert.match(documents, /ticket\.markTicketReceivable\(\)/);
  assert.match(receivables, /tipo == CommercialDocumentType\.TICKET && cuentaCobrar/);
  assert.match(receivables, /customer_receivable_customer_required/);
});
