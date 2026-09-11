import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { customerIdentityFailure } from "./customerDocumentIdentity";

describe("customer identity failures", () => {
  it.each(["es", "en", "zh"] as const)("translates every structured backend code in %s", (locale) => {
    const t = createTranslator(locale);
    for (const code of ["CUSTOMER_DOCUMENT_INVALID", "CUSTOMER_DOCUMENT_DUPLICATE", "CUSTOMER_IDENTITY_SAAS_UNAVAILABLE", "CUSTOMER_IDENTITY_CONFLICT"]) {
      const failure = customerIdentityFailure(new ApiError("Unusable detail", 503, { code }));
      expect(failure).toBeDefined();
      expect(t(failure!.messageKey)).not.toBe(failure!.messageKey);
      expect(t(failure!.messageKey)).not.toContain("Unusable detail");
    }
  });

  it("does not reinterpret unrelated or unstructured errors", () => {
    expect(customerIdentityFailure(new ApiError("Other problem", 400, { code: "OTHER" }))).toBeUndefined();
    expect(customerIdentityFailure(new Error("CUSTOMER_DOCUMENT_INVALID"))).toBeUndefined();
  });
});
