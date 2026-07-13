import { describe,expect,it } from "vitest";
import { compensationGuidance,paymentSessionLocksSale,stableAllocationAttempt } from "./SalePaymentCheckout";
describe("SalePaymentCheckout locking and cancellation",()=>{
 it("freezes sale controls for every active or compensation state",()=>{expect(paymentSessionLocksSale("COLLECTING")).toBe(true);expect(paymentSessionLocksSale("COVERED")).toBe(true);expect(paymentSessionLocksSale("COMPENSATION_REQUIRED")).toBe(true);});
 it("unfreezes only after finalization or safe cancellation",()=>{expect(paymentSessionLocksSale("FINALIZED")).toBe(false);expect(paymentSessionLocksSale("CANCELLED")).toBe(false);});
 it("keeps approved payments visible and explains every compensation path",()=>{expect(compensationGuidance).toContain("siguen visibles");expect(compensationGuidance).toContain("Anula/reembolsa");expect(compensationGuidance).toContain("resolución administrativa");});
 it("reuses the persisted allocation id after an uncertain response",()=>{const input={kind:"INTEGRATED_CARD",amountCents:1000,provider:"PAYTEF"};const first=stableAllocationAttempt(null,"session",input,()=>"stable-id");expect(stableAllocationAttempt(first,"session",input,()=>"duplicate-id").allocationId).toBe("stable-id");});
});
