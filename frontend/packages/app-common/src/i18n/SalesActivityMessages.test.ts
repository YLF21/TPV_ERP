import { describe, expect, it } from "vitest";
import { createSalesActivityTranslator } from "./SalesActivityMessages";

describe("SalesActivityMessages", () => {
  it("provides integrated payment labels in every supported locale", () => {
    expect({
      cash: createSalesActivityTranslator("es")("cash"),
      card: createSalesActivityTranslator("es")("card"),
      transfer: createSalesActivityTranslator("es")("transfer"),
      voucher: createSalesActivityTranslator("es")("voucher"),
      pending: createSalesActivityTranslator("es")("pending"),
      other: createSalesActivityTranslator("es")("other")
    }).toEqual({ cash: "Efectivo", card: "Tarjeta", transfer: "Transferencia", voucher: "Vale", pending: "Pendiente de cobro", other: "Otros" });
    expect({
      cash: createSalesActivityTranslator("en")("cash"),
      card: createSalesActivityTranslator("en")("card"),
      transfer: createSalesActivityTranslator("en")("transfer"),
      voucher: createSalesActivityTranslator("en")("voucher"),
      pending: createSalesActivityTranslator("en")("pending"),
      other: createSalesActivityTranslator("en")("other")
    }).toEqual({ cash: "Cash", card: "Card", transfer: "Transfer", voucher: "Voucher", pending: "Pending collection", other: "Other" });
    expect({
      cash: createSalesActivityTranslator("zh")("cash"),
      card: createSalesActivityTranslator("zh")("card"),
      transfer: createSalesActivityTranslator("zh")("transfer"),
      voucher: createSalesActivityTranslator("zh")("voucher"),
      pending: createSalesActivityTranslator("zh")("pending"),
      other: createSalesActivityTranslator("zh")("other")
    }).toEqual({ cash: "现金", card: "银行卡", transfer: "转账", voucher: "代金券", pending: "待收款", other: "其他" });
  });

  it("keeps the operations section label locale-specific", () => {
    expect(createSalesActivityTranslator("es")("operationsSection")).toBe("Operativa del día");
    expect(createSalesActivityTranslator("en")("operationsSection")).toBe("Daily operations");
    expect(createSalesActivityTranslator("zh")("operationsSection")).toBe("当日运营");
  });
});
