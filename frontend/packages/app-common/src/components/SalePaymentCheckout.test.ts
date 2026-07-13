import { describe,expect,it } from "vitest";
import { compensationGuidance,paymentSessionLocksSale } from "./SalePaymentCheckout";
describe("SalePaymentCheckout locking and cancellation",()=>{
 it("freezes sale controls for every active or compensation state",()=>{expect(paymentSessionLocksSale("COLLECTING")).toBe(true);expect(paymentSessionLocksSale("COVERED")).toBe(true);expect(paymentSessionLocksSale("COMPENSATION_REQUIRED")).toBe(true);});
 it("unfreezes only after finalization or safe cancellation",()=>{expect(paymentSessionLocksSale("FINALIZED")).toBe(false);expect(paymentSessionLocksSale("CANCELLED")).toBe(false);});
 it("keeps approved payments visible and explains every compensation path",()=>{expect(compensationGuidance).toContain("siguen visibles");expect(compensationGuidance).toContain("Anula/reembolsa");expect(compensationGuidance).toContain("resolución administrativa");});
});
