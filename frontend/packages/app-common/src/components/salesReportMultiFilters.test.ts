import { describe, expect, it } from "vitest";
import { normalizeReportPayment, reportMultiFilterExport, reportMultiFilterOptions, reportPaymentValues,
  rowMatchesReportMultiFilters } from "./salesReportMultiFilters";

const t = (key: string) => key;

describe("report multi-value criteria", () => {
  it("orders payment methods like checkout, merges aliases and puts custom methods last", () => {
    const rows = ["Zeta", "MEMBER_CREDIT", "TRANSFER", "CARD", "MEMBER_BALANCE", "DISCOUNT", "PENDING", "VOUCHER", "CASH", "Alfa", "efectivo"]
      .map(payment => ({ payment }));
    expect(reportMultiFilterOptions(rows, "payment", "es", t).map(option => option.value)).toEqual([
      "EFECTIVO", "TARJETA", "VALE", "salesReport.payment.pending", "TRANSFERENCIA", "DESCUENTO",
      "salesReport.payment.memberBalance", "salesReport.payment.returnCredit", "Alfa", "Zeta"
    ]);
  });

  it("retains the payment order when selected methods disappear from a new period", () => {
    expect(reportMultiFilterOptions([{ payment: "CARD" }], "payment", "es", t, ["EFECTIVO", "TRANSFERENCIA"])
      .map(option => option.value)).toEqual(["EFECTIVO", "TARJETA", "TRANSFERENCIA"]);
  });

  it("matches either payment within a field and every different field without duplicating a mixed document", () => {
    const rows = [
      { ticket: "mixed", payment: "EFECTIVO + TARJETA", user: "ANA", status: "confirmed" },
      { ticket: "card", payment: "CARD", user: "ANA", status: "confirmed" },
      { ticket: "cash-other-user", payment: "CASH", user: "LUIS", status: "confirmed" },
      { ticket: "transfer", payment: "TRANSFER", user: "ANA", status: "confirmed" },
      { ticket: "cancelled", payment: "CASH", user: "ANA", status: "cancelled" }
    ];
    expect(rows.filter(row => rowMatchesReportMultiFilters(row, {
      payment: ["CASH", "TARJETA"], user: ["ANA"], status: ["confirmed"]
    })).map(row => row.ticket)).toEqual(["mixed", "card"]);
  });

  it.each(["customer", "supplier"] as const)("matches %s identities exactly and only falls back to name without code", field => {
    expect(rowMatchesReportMultiFilters({ [field]: "C-10", [`${field}Name`]: "Norte" }, { [field]: ["C-1"] })).toBe(false);
    expect(rowMatchesReportMultiFilters({ [field]: "C-1", [`${field}Name`]: "Norte" }, { [field]: ["C-1", "C-2"] })).toBe(true);
    expect(rowMatchesReportMultiFilters({ [`${field}Name`]: "Norte" }, { [field]: ["Norte"] })).toBe(true);
    expect(reportMultiFilterOptions([{ [field]: "C-1", [`${field}Name`]: "Norte" }], field, "es", t))
      .toEqual([{ value: "C-1", label: "C-1 · Norte" }]);
  });

  it("retains a plus sign inside a custom method when individual method metadata exists", () => {
    expect(reportPaymentValues({ payment: "CASH + Bono + Empresa", __filterPaymentMethods: '["CASH","Bono + Empresa"]' }))
      .toEqual(["EFECTIVO", "Bono + Empresa"]);
    expect(reportPaymentValues({ payment: "CASH + CARD", __filterPaymentMethods: "invalid-json" })).toEqual(["EFECTIVO", "TARJETA"]);
    expect(normalizeReportPayment("—")).toBe("");
  });

  it("combines warehouse and terminal alternatives, omits empty criteria and serializes unique plural values", () => {
    const filters = { warehouse: ["GENERAL", "RESERVA", "GENERAL"], terminal: ["CAJA1", "CAJA2"], user: [] };
    expect(rowMatchesReportMultiFilters({ warehouse: "RESERVA", terminal: "CAJA2" }, filters)).toBe(true);
    expect(rowMatchesReportMultiFilters({ warehouse: "GENERAL", terminal: "CAJA3" }, filters)).toBe(false);
    expect(reportMultiFilterExport(filters)).toEqual({ warehouses: ["GENERAL", "RESERVA"], terminals: ["CAJA1", "CAJA2"] });
  });
});
