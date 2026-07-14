// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, describe,expect,it,vi } from "vitest";
import { authorizationPasswordIsEphemeral,canManuallyFinalizePayment,compensationNoteIsEphemeral,compensationGuidanceKey,paymentSessionAfterFinalization,paymentSessionLocksSale,stableAllocationAttempt,allocationFailureIsSafePreEffect } from "./SalePaymentCheckout";
import { SalePaymentCheckout } from "./SalePaymentCheckout";
import { ApiError } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";

const { apiRequestMock } = vi.hoisted(() => ({ apiRequestMock: vi.fn() }));
vi.mock("../api/client", async (importOriginal) => ({
 ...(await importOriginal<typeof import("../api/client")>()),
 apiRequest: apiRequestMock,
}));

afterEach(() => {
 cleanup();
 apiRequestMock.mockReset();
 localStorage.clear();
 sessionStorage.clear();
});

describe("SalePaymentCheckout locking and cancellation",()=>{
 it("synchronously ignores a second cash confirmation before React disables the dialog",async()=>{
  const session={id:"session-guard",total:"12.10",status:"COLLECTING",allocations:[]};
  let releaseSession!:(value:typeof session)=>void;
  const pendingSession=new Promise<typeof session>(resolve=>{releaseSession=resolve;});
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return pendingSession;
   if(path==="/pos/payment-sessions/session-guard/allocations")return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"CASH",amount:"12.10"}]};
   if(path==="/pos/payment-sessions/session-guard/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-GUARD"};
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));
  fireEvent.click(screen.getByRole("button",{name:/Efectivo/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  const confirm=screen.getByRole("button",{name:"Confirmar cobro"});

  act(()=>{confirm.click();window.dispatchEvent(new KeyboardEvent("keydown",{key:"Enter",bubbles:true,cancelable:true}));});
  expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions")).toHaveLength(1);
  releaseSession(session);

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith("T-GUARD",1210,2000));
  expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-guard/allocations")).toHaveLength(1);
 });

 it("does not leak received cash into a manual-card finalize after a safe cash failure",async()=>{
  const session={id:"session-safe",total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationAttempt=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-safe/allocations"){
    allocationAttempt+=1;
    if(allocationAttempt===1)throw new ApiError("cash rejected safely",422);
    return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"MANUAL_CARD",amount:"12.10"}]};
   }
   if(path==="/pos/payment-sessions/session-safe/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-MANUAL"};
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));
  fireEvent.click(screen.getByRole("button",{name:/Efectivo/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(screen.getAllByText("cash rejected safely")).not.toHaveLength(0));
  fireEvent.click(screen.getByRole("button",{name:"Cancelar"}));

  fireEvent.click(screen.getByRole("button",{name:"Tarjeta manual"}));
  const manualDialog=screen.getByRole("dialog",{name:"Cobro con tarjeta manual"});
  fireEvent.change(within(manualDialog).getByRole("textbox"),{target:{value:"REF-1"}});
  fireEvent.click(within(manualDialog).getByRole("button",{name:"Confirmar"}));

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith("T-MANUAL",1210,undefined));
 });

 it("opens the touch cash calculator and submits the total exactly once",async()=>{
  const session={id:"session-1",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-1/allocations")return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"CASH",amount:"12.10"}]};
   if(path==="/pos/payment-sessions/session-1/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-1"};
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));

  fireEvent.click(screen.getByRole("button",{name:/Efectivo/}));
  expect(screen.getByRole("button",{name:"Tecla 7"})).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Usar teclado físico"}));
  fireEvent.click(screen.getByRole("button",{name:"Cancelar"}));
  fireEvent.click(screen.getByRole("button",{name:/Efectivo/}));
  expect(screen.getByRole("button",{name:"Tecla 7"})).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith("T-1",1210,2000));
  const allocationCalls=apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-1/allocations");
  expect(allocationCalls).toHaveLength(1);
  expect(allocationCalls[0][1].body).toMatchObject({kind:"CASH",amount:"12.10"});
 });
 it("freezes sale controls for every active or compensation state",()=>{expect(paymentSessionLocksSale("COLLECTING")).toBe(true);expect(paymentSessionLocksSale("COVERED")).toBe(true);expect(paymentSessionLocksSale("COMPENSATION_REQUIRED")).toBe(true);});
 it("unfreezes only after finalization or safe cancellation",()=>{expect(paymentSessionLocksSale("FINALIZED")).toBe(false);expect(paymentSessionLocksSale("CANCELLED")).toBe(false);});
 it("offers an explicit idempotent finalize retry for a recovered covered session",()=>{expect(canManuallyFinalizePayment("COVERED",false)).toBe(true);expect(canManuallyFinalizePayment("COVERED",true)).toBe(false);expect(canManuallyFinalizePayment("COLLECTING",false)).toBe(false);});
 it("removes the completed checkout panel after a real ticket is returned",()=>{const session={status:"FINALIZED"};expect(paymentSessionAfterFinalization("001-260713-00001",session)).toBeNull();expect(paymentSessionAfterFinalization(undefined,session)).toBe(session);});
 it("keeps approved payments visible and explains every compensation path",()=>{const guidance=createTranslator("es")(compensationGuidanceKey);expect(guidance).toContain("siguen visibles");expect(guidance).toContain("Anula o reembolsa");expect(guidance).toContain("resolución administrativa");});
 it("reuses the persisted allocation id after an uncertain response",()=>{const input={kind:"INTEGRATED_CARD",amountCents:1000,provider:"PAYTEF"};const first=stableAllocationAttempt(null,"session",input,()=>"stable-id");expect(stableAllocationAttempt(first,"session",input,()=>"duplicate-id").allocationId).toBe("stable-id");});
 it("clears an attempt only for known safe 4xx pre-effect failures",()=>{expect(allocationFailureIsSafePreEffect(new ApiError("validation",400))).toBe(true);expect(allocationFailureIsSafePreEffect(new ApiError("conflict",409))).toBe(true);});
 it("keeps an attempt for 5xx and unknown HTTP outcomes",()=>{expect(allocationFailureIsSafePreEffect(new ApiError("server",500))).toBe(false);expect(allocationFailureIsSafePreEffect(new ApiError("unknown",418))).toBe(false);});
 it("clears an authorization password before invoking the payment operation",async()=>{let visible="secret";await authorizationPasswordIsEphemeral(visible,value=>{visible=value;},async password=>{expect(visible).toBe("");expect(password).toBe("secret");});expect(visible).toBe("");});
 it("clears and trims the compensation note before sending it",async()=>{let visible=" resolución ";await compensationNoteIsEphemeral(visible,value=>{visible=value;},async note=>{expect(visible).toBe("");expect(note).toBe("resolución");});expect(visible).toBe("");});
});
