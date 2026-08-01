// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement,createRef } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
 allocationFailureIsSafePreEffect,
 authorizationPasswordIsEphemeral,
 canManuallyFinalizePayment,
 checkoutPresentation,
 compensationGuidanceKey,
 compensationNoteIsEphemeral,
 entryCleanupRegistrySizeForTest,
 isCreditLimitExceededError,
 isMissingCashSessionError,
 paymentLogoutDisposition,
 paymentSessionAfterFinalization,
 paymentSessionLocksSale,
 prepareAutomaticExit,
 allocationRecoveryInput,
 stableAllocationAttempt,
 shouldOfferTestCashSession,
 shouldFinalizeAfterAllocation,
} from "./SalePaymentCheckout";
import { SalePaymentCheckout, type SalePaymentCheckoutHandle, type ServerSession } from "./SalePaymentCheckout";
import { ApiError } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { ConfirmedTicketPrintSnapshot } from "../sale/ticketPrinting";

const printTicket = (documentNumber: string): ConfirmedTicketPrintSnapshot => ({
 documentId: `document-${documentNumber}`,
 documentNumber,
 issuedAt: "2026-07-15T12:00:00.000Z",
 lines: [],
 payments: [],
 total: "12.10",
});

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
 it("recognizes the missing cash-session error with or without accents", () => {
  expect(isMissingCashSessionError("No hay una sesion de caja abierta")).toBe(true);
  expect(isMissingCashSessionError("No hay una sesión de caja abierta")).toBe(true);
  expect(isMissingCashSessionError("La terminal no existe")).toBe(false);
 });
 it("recognizes only a credit-limit conflict as overridable",()=>{
  expect(isCreditLimitExceededError(new ApiError("localized",409,{code:"CUSTOMER_CREDIT_LIMIT_EXCEEDED"}))).toBe(true);
  expect(isCreditLimitExceededError(new ApiError("La operaci\u00f3n supera el l\u00edmite de cr\u00e9dito del cliente",409,{code:"STATE_CONFLICT"}))).toBe(false);
  expect(isCreditLimitExceededError(new ApiError("localized",400,{code:"CUSTOMER_CREDIT_LIMIT_EXCEEDED"}))).toBe(false);
 });
 it("offers test cash only for an enabled covered checkout with a terminal", () => {
  expect(shouldOfferTestCashSession(true, "COVERED", true, "terminal-1")).toBe(true);
  expect(shouldOfferTestCashSession(false, "COVERED", true, "terminal-1")).toBe(false);
  expect(shouldOfferTestCashSession(true, "COLLECTING", true, "terminal-1")).toBe(false);
  expect(shouldOfferTestCashSession(true, "COVERED", false, "terminal-1")).toBe(false);
  expect(shouldOfferTestCashSession(true, "COVERED", true, undefined)).toBe(false);
 });
 it("auto-finalizes only a covered Enter flow in unified checkout", () => {
  expect(shouldFinalizeAfterAllocation("COVERED", true, "CASH", true)).toBe(true);
 expect(shouldFinalizeAfterAllocation("COLLECTING", true, "CASH", true)).toBe(false);
  expect(shouldFinalizeAfterAllocation("COVERED", true, "CASH", false)).toBe(false);
  expect(shouldFinalizeAfterAllocation("COVERED", true, "MANUAL_CARD", true)).toBe(true);
  expect(shouldFinalizeAfterAllocation("COVERED", true, "INTEGRATED_CARD", false)).toBe(true);
 });
 it("finalizes a covered unified checkout when the operator presses Enter", async () => {
  const collecting={id:"session-unified-enter",total:"12.10",status:"COLLECTING",allocations:[]};
  const covered={
   ...collecting,
   status:"COVERED",
   allocations:[{
    id:"cash-enter",
    idempotencyKey:"cash-enter",
    kind:"CASH",
    amount:"12.10",
    delivered:"12.10",
    change:"0.00",
    status:"APPROVED",
   }],
  };
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return collecting;
   if(path==="/pos/payment-sessions/session-unified-enter/allocations")return covered;
   if(path==="/pos/payment-sessions/session-unified-enter/finalize")return {...covered,status:"FINALIZED",ticketNumber:"T-ENTER",printTicket:printTicket("T-ENTER")};
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{
   ref,
   locale:"es",
   totalCents:1210,
   sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},
   permissions:[],
   terminal:{storeName:"Tienda",terminalCode:"01"},
   unifiedCheckout:true,
   onFinalized,
  }));
  await waitFor(()=>expect(ref.current).not.toBeNull());

  act(()=>ref.current!.openCheckout("CASH"));
  const amount=await screen.findByRole("textbox",{name:/IMPORTE/});
  fireEvent.keyDown(amount,{key:"Enter"});

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith(
   printTicket("T-ENTER"),
   {kind:"CASH",totalCents:1210,receivedCents:1210},
  ));
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/finalize"))).toHaveLength(1);
 });
 it("authorizes pending credit and a later credit-limit override with separate ephemeral credentials",async()=>{
  const collecting={id:"session-pending-auth",total:"12.10",status:"COLLECTING",allocations:[]};
  const covered={
   ...collecting,
   status:"COVERED",
   allocations:[{
    id:"pending-auth",
    idempotencyKey:"pending-auth",
    kind:"PENDING",
    amount:"12.10",
    status:"APPROVED",
   }],
  };
  let finalizeAttempts=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return collecting;
   if(path==="/pos/payment-sessions/session-pending-auth/allocations")return covered;
   if(path==="/pos/payment-sessions/session-pending-auth/finalize"){
    finalizeAttempts+=1;
    if(finalizeAttempts===1)throw new ApiError(
     "La operaci\u00f3n supera el l\u00edmite de cr\u00e9dito del cliente",
     409,
     {code:"CUSTOMER_CREDIT_LIMIT_EXCEEDED"},
    );
    expect(options?.body).toEqual({
     authorizerUsername:"pending-manager",
     authorizerPassword:"pending-password-2",
     creditOverride:{
      reason:"Excepci\u00f3n autorizada",
      authorizerUsername:"credit-manager",
      authorizerPassword:"credit-password",
     },
    });
    return {...covered,status:"FINALIZED",ticketNumber:"T-PENDING",printTicket:printTicket("T-PENDING")};
   }
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{
   ref,
   locale:"es",
   totalCents:1210,
   sale:{customerId:"customer-1",lines:[{productId:"p-1",quantity:1,discount:0}]},
   permissions:[],
   terminal:{storeName:"Tienda",terminalCode:"01"},
   unifiedCheckout:true,
   customerSelected:true,
   createPendingAuthorization:{mode:"DELEGATED",requireUsername:true,requirePassword:true},
   creditOverrideAuthorization:{mode:"DELEGATED",requireUsername:true,requirePassword:true},
   onFinalized,
  }));
  await waitFor(()=>expect(ref.current).not.toBeNull());

  act(()=>ref.current!.openCheckout("PENDING"));
  const amount=await screen.findByRole("textbox",{name:/IMPORTE/});
  fireEvent.keyDown(amount,{key:"Enter"});

  let authorizationDialog=await screen.findByRole("dialog",{name:/autorizaci.*pendiente/i});
  fireEvent.change(within(authorizationDialog).getByRole("textbox",{name:/usuario autorizador/i}),{target:{value:"pending-manager"}});
  fireEvent.change(within(authorizationDialog).getByLabelText(/contrase.*autorizador/i),{target:{value:"pending-password-1"}});
  fireEvent.click(within(authorizationDialog).getByRole("button",{name:/confirmar/i}));

  authorizationDialog=await screen.findByRole("dialog",{name:/supera las reglas de cr.*dito/i});
  const usernames=within(authorizationDialog).getAllByRole("textbox",{name:/usuario autorizador/i});
  const passwords=within(authorizationDialog).getAllByLabelText(/contrase.*autorizador/i);
  fireEvent.change(usernames[0],{target:{value:"pending-manager"}});
  fireEvent.change(passwords[0],{target:{value:"pending-password-2"}});
  fireEvent.change(within(authorizationDialog).getByRole("textbox",{name:/motivo de la autorizaci.*n/i}),{target:{value:"Excepci\u00f3n autorizada"}});
  fireEvent.change(usernames[1],{target:{value:"credit-manager"}});
  fireEvent.change(passwords[1],{target:{value:"credit-password"}});
  fireEvent.click(within(authorizationDialog).getByRole("button",{name:/confirmar/i}));

  await waitFor(()=>expect(onFinalized).toHaveBeenCalled());
  expect(finalizeAttempts).toBe(2);
 });
 it("keeps a partial Enter checkout open and returns focus to the remaining amount", async () => {
  const collecting={id:"session-unified-partial",total:"12.10",status:"COLLECTING",allocations:[]};
  const partial={
   ...collecting,
   allocations:[{
    id:"cash-partial",
    idempotencyKey:"cash-partial",
    kind:"CASH",
    amount:"5.00",
    delivered:"5.00",
    change:"0.00",
    status:"APPROVED",
   }],
  };
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return collecting;
   if(path==="/pos/payment-sessions/session-unified-partial/allocations")return partial;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{
   ref,
   locale:"es",
   totalCents:1210,
   sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},
   permissions:[],
   terminal:{storeName:"Tienda",terminalCode:"01"},
   unifiedCheckout:true,
   onFinalized:vi.fn(),
  }));
  await waitFor(()=>expect(ref.current).not.toBeNull());

  act(()=>ref.current!.openCheckout("CASH"));
  const amount=await screen.findByRole("textbox",{name:/IMPORTE/});
  fireEvent.change(amount,{target:{value:"5,00"}});
  fireEvent.keyDown(amount,{key:"Enter"});

  await waitFor(()=>expect(amount).toHaveValue("7,10"));
  await waitFor(()=>expect(amount).toHaveFocus());
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/finalize"))).toHaveLength(0);
 });
 it("classifies logout safety from hydration, session, and allocation state",()=>{
  const sessionWith=(statuses:string[])=>({id:"session",total:"12.10",status:"COLLECTING",allocations:statuses.map((status,index)=>({id:`a-${index}`,idempotencyKey:`a-${index}`,kind:"CASH" as const,amount:"1.00",status}))});
  expect(paymentLogoutDisposition(null,true)).toBe("READY");
  expect(paymentLogoutDisposition(sessionWith([]),true)).toBe("AUTO_CANCEL");
  expect(paymentLogoutDisposition(sessionWith(["DECLINED","ERROR"]),true)).toBe("AUTO_CANCEL");
  for(const status of ["PENDING","TIMEOUT","APPROVED"])expect(paymentLogoutDisposition(sessionWith([status]),true)).toBe("BLOCKED");
  expect(paymentLogoutDisposition({...sessionWith([]),status:"COVERED"},true)).toBe("BLOCKED");
  expect(paymentLogoutDisposition({...sessionWith([]),status:"COMPENSATION_REQUIRED"},true)).toBe("BLOCKED");
  expect(paymentLogoutDisposition(null,false)).toBe("BLOCKED");
 });
 it("prepares logout immediately after an empty active-session hydration",async()=>{
  let resolveActive!:(value:null)=>void;
  const activeResponse=new Promise<null>(resolve=>{resolveActive=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return activeResponse;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(apiRequestMock).toHaveBeenCalledWith("/pos/payment-sessions/active",expect.anything()));
  await act(async()=>{resolveActive(null);await activeResponse;});
  await expect(ref.current!.prepareLogout()).resolves.toBe("READY");
 });
 it("reports hydration separately from the lock and stays locked for a recovered session",async()=>{
  let resolveActive!:(value:ServerSession)=>void;
  const activeResponse=new Promise<ServerSession>(resolve=>{resolveActive=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return activeResponse;
   if(path.endsWith("/simulator-discard"))throw new ApiError("terminal live",409);
   throw new Error(`unexpected request ${path}`);
  });
  const onLockedChange=vi.fn();const onHydrationChange=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onHydrationChange,onLockedChange,onFinalized:vi.fn()}));
  await waitFor(()=>expect(apiRequestMock).toHaveBeenCalledWith("/pos/payment-sessions/active",expect.anything()));
  expect(onHydrationChange).toHaveBeenLastCalledWith(false);
  expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined);
  const active={id:"active-slow",total:"12.10",status:"COLLECTING",allocations:[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"TIMEOUT"}]};
  await act(async()=>{resolveActive(active);await activeResponse;});
  await waitFor(()=>expect(onHydrationChange).toHaveBeenLastCalledWith(true));
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(true,1210));
 });
 it("auto-cancels a collecting session whose declined and errored allocations are safely terminal",async()=>{
  const storageKey="tpverp.payment-session.01";
  const attemptKey=`${storageKey}.allocation-attempt`;
  const session={id:"session-terminal-failures",total:"12.10",status:"COLLECTING",allocations:[
   {id:"a-declined",idempotencyKey:"a-declined",kind:"INTEGRATED_CARD",amount:"6.05",status:"DECLINED"},
   {id:"a-error",idempotencyKey:"a-error",kind:"MANUAL_CARD",amount:"6.05",status:"ERROR"},
  ]};
  localStorage.setItem(attemptKey,"stable-attempt");
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path==="/pos/payment-sessions/session-terminal-failures/cancel")return {...session,status:"CANCELLED"};
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(sessionStorage.getItem(storageKey)).toBe(session.id));
  await expect(ref.current!.prepareLogout()).resolves.toBe("READY");
  expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-terminal-failures/cancel")).toHaveLength(1);
  expect(sessionStorage.getItem(storageKey)).toBeNull();
  expect(localStorage.getItem(attemptKey)).toBeNull();
 });
 it("does not recover or lock the old sale after safe cancellation and remount",async()=>{
  const session={id:"session-remount",total:"12.10",status:"COLLECTING",allocations:[]};
  let activeCalls=0;
  let resolveSecondActive!:(value:null)=>void;
  const secondActive=new Promise<null>(resolve=>{resolveSecondActive=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return ++activeCalls===1?session:secondActive;
   if(path==="/pos/payment-sessions/session-remount/cancel")return {...session,status:"CANCELLED"};
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const firstRef=createRef<SalePaymentCheckoutHandle>();
  const first=render(createElement(SalePaymentCheckout,{...props,ref:firstRef}));
  await screen.findByRole("button",{name:"Cancelar sesión de cobro"});
  await expect(firstRef.current!.prepareLogout()).resolves.toBe("READY");
  first.unmount();
  const onLockedChange=vi.fn();
  const secondRef=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{...props,ref:secondRef,onLockedChange}));
  await waitFor(()=>expect(activeCalls).toBe(2));
  await act(async()=>{resolveSecondActive(null);await secondActive;});
  await expect(secondRef.current!.prepareLogout()).resolves.toBe("READY");
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/cancel"))).toHaveLength(1);
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));
  expect(screen.queryByRole("button",{name:"Cancelar sesión de cobro"})).not.toBeInTheDocument();
 });
 it("cancels a safe empty collecting session before allowing logout",async()=>{
  const session={id:"session-logout",total:"12.10",status:"COLLECTING",allocations:[]};
  const cancelRequest=vi.fn();
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path==="/pos/payment-sessions/session-logout/cancel"){cancelRequest();return {...session,status:"CANCELLED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await screen.findByRole("button",{name:"Cancelar sesión de cobro"});
  await expect(ref.current!.prepareLogout()).resolves.toBe("READY");
  expect(cancelRequest).toHaveBeenCalledTimes(1);
 });
 it("blocks logout for an unresolved payment without cancelling it",async()=>{
  const session={id:"session-pending",total:"12.10",status:"COLLECTING",allocations:[{id:"a-1",idempotencyKey:"a-1",kind:"INTEGRATED_CARD",amount:"12.10",status:"PENDING"}]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await screen.findByRole("button",{name:"Cancelar sesión de cobro"});
  await expect(ref.current!.prepareLogout()).resolves.toBe("BLOCKED");
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/cancel"))).toHaveLength(0);
  expect(await screen.findByRole("alert")).toHaveTextContent("Debes resolver el cobro pendiente");
 });
 it("keeps payment recovery state when automatic cancellation has an uncertain outcome",async()=>{
  const session={id:"session-cancel-error",total:"12.10",status:"COLLECTING",allocations:[]};
  sessionStorage.setItem("tpverp.payment-session.01",session.id);
  localStorage.setItem("tpverp.payment-session.01.allocation-attempt",JSON.stringify({sessionId:session.id,allocationId:"stable",input:{kind:"CASH",amountCents:1210}}));
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path==="/pos/payment-sessions/session-cancel-error/cancel")throw new ApiError("network uncertain",500);
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await screen.findByRole("button",{name:"Cancelar sesión de cobro"});
  await expect(ref.current!.prepareLogout()).resolves.toBe("BLOCKED");
  expect(sessionStorage.getItem("tpverp.payment-session.01")).toBe(session.id);
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toContain("stable");
  expect(await screen.findByRole("alert")).toHaveTextContent("Debes resolver el cobro pendiente");
 });
 it("blocks logout when active-session hydration fails without a stored recovery id",async()=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")throw new ApiError("offline",500);
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(ref.current).not.toBeNull());
  await expect(ref.current!.prepareLogout()).resolves.toBe("BLOCKED");
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/cancel"))).toHaveLength(0);
 });
 it("blocks logout and preserves recovery data when active and stored-session hydration both fail",async()=>{
  const storageKey="tpverp.payment-session.01";
  sessionStorage.setItem(storageKey,"session-recovery");
  localStorage.setItem(`${storageKey}.allocation-attempt`,"stable-recovery");
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active"||path==="/pos/payment-sessions/session-recovery")throw new ApiError("offline",500);
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(apiRequestMock).toHaveBeenCalledWith("/pos/payment-sessions/session-recovery",expect.anything()));
  await expect(ref.current!.prepareLogout()).resolves.toBe("BLOCKED");
  expect(sessionStorage.getItem(storageKey)).toBe("session-recovery");
  expect(localStorage.getItem(`${storageKey}.allocation-attempt`)).toBe("stable-recovery");
 });
 it("translates the pending-payment logout error in every supported locale",()=>{
  for(const locale of ["es","en","zh"] as const)expect(createTranslator(locale)("payment.pending.logoutError")).not.toBe("payment.pending.logoutError");
 });
 it("does not issue a second cleanup request after cancellation fails",async()=>{
  const discard=vi.fn(async()=>true);
  await expect(prepareAutomaticExit(async()=>"ERROR",discard)).resolves.toBe("BLOCKED");
  expect(discard).not.toHaveBeenCalled();
 });
 it.each(["logout","application close"])("blocks %s without discard when cancellation is not confirmed CANCELLED",async()=>{
  const cancel=vi.fn(async()=>"NOT_CANCELLED" as const);
  const discard=vi.fn(async()=>true);
  await expect(prepareAutomaticExit(cancel,discard)).resolves.toBe("BLOCKED");
  expect(cancel).toHaveBeenCalledTimes(1);
  expect(discard).not.toHaveBeenCalled();
 });
 it("exposes the same enabled cash and card actions through its imperative handle",async()=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  const onCash=vi.fn();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onCash,onFinalized:vi.fn()}));
  await waitFor(()=>expect(screen.getByRole("button",{name:/Tarjeta/})).toBeEnabled());

  act(()=>ref.current!.triggerCash());
  expect(onCash).toHaveBeenCalledTimes(1);

  act(()=>ref.current!.triggerCard());
  expect(screen.getByRole("dialog",{name:"Cobro con tarjeta manual"})).toBeInTheDocument();
 });
 it.each([
  ["logout","prepareLogout"],
  ["application close","prepareApplicationClose"],
 ] as const)("does not issue a second HTTP cleanup request for %s after a non-CANCELLED cancel response",async(label,method)=>{
  const session={id:`session-non-cancelled-${label.replace(" ","-")}`,total:"12.10",status:"COLLECTING",allocations:[]};
  const cancelRequest=vi.fn();const discardRequest=vi.fn();
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){discardRequest();return session;}
   if(path.endsWith("/cancel")){cancelRequest();return {...session,status:"COMPENSATION_REQUIRED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(discardRequest).toHaveBeenCalledTimes(1));
  await expect(ref.current![method]()).resolves.toBe("BLOCKED");
  expect(cancelRequest).toHaveBeenCalledTimes(1);
  expect(discardRequest).toHaveBeenCalledTimes(1);
 });
 it("prepares application close by discarding an uncertain simulator session and clearing recovery",async()=>{
  const storageKey="tpverp.payment-session.01";const attemptKey=`${storageKey}.allocation-attempt`;
  const session={id:"session-shutdown",total:"12.10",status:"COMPENSATION_REQUIRED",allocations:[{id:"a-1",idempotencyKey:"a-1",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"TIMEOUT"}]};
  sessionStorage.setItem(storageKey,session.id);localStorage.setItem(attemptKey,"stable");
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){
    expect(options?.body).toEqual({
     reason: expect.stringMatching(/^(application_shutdown|sale_entry_cleanup)$/),
    });
    return {...session,status:"CANCELLED"};
   }
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();const onLockedChange=vi.fn();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onLockedChange,onFinalized:vi.fn()}));
  await waitFor(()=>expect(sessionStorage.getItem(storageKey)).toBe(session.id));
  await expect(ref.current!.prepareApplicationClose()).resolves.toBe("READY");
  expect(sessionStorage.getItem(storageKey)).toBeNull();expect(localStorage.getItem(attemptKey)).toBeNull();
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));
 });
 it("blocks logout when simulator discard is not confirmed CANCELLED",async()=>{
  const session={id:"session-logout-unsafe",total:"12.10",status:"COMPENSATION_REQUIRED",allocations:[]};
  sessionStorage.setItem("tpverp.payment-session.01",session.id);
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard"))return session;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await screen.findByRole("button",{name:"Cancelar sesión de cobro"});
  await expect(ref.current!.prepareLogout()).resolves.toBe("BLOCKED");
  expect(sessionStorage.getItem("tpverp.payment-session.01")).toBe(session.id);
 });
 it("blocks application close on live-mode rejection and preserves recovery",async()=>{
  const storageKey="tpverp.payment-session.01";const attemptKey=`${storageKey}.allocation-attempt`;
  const session={id:"session-live",total:"12.10",status:"COLLECTING",allocations:[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"PENDING"}]};
  localStorage.setItem(attemptKey,"stable");
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard"))throw new ApiError("terminal live",409);
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(sessionStorage.getItem(storageKey)).toBe(session.id));
  await expect(ref.current!.prepareApplicationClose()).resolves.toBe("BLOCKED");
  expect(sessionStorage.getItem(storageKey)).toBe(session.id);expect(localStorage.getItem(attemptKey)).toBe("stable");
  expect(await screen.findByRole("alert")).toHaveTextContent("Debes resolver el cobro pendiente antes de cerrar la aplicación");
 });
 it("attempts stale-session entry cleanup only once and clears the lock after CANCELLED",async()=>{
  const session={id:"session-stale",total:"12.10",status:"COVERED",allocations:[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"APPROVED"}]};
  const discard=vi.fn();
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){discard(options?.body);return {...session,status:"CANCELLED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const onLockedChange=vi.fn();const view=render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onLockedChange,onFinalized:vi.fn()}));
  await waitFor(()=>expect(discard).toHaveBeenCalledWith({reason:"sale_entry_cleanup"}));
  view.rerender(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onLockedChange,onFinalized:vi.fn()}));
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));expect(discard).toHaveBeenCalledTimes(1);
 });
 it("shares one in-flight cleanup across entry, logout, and application close",async()=>{
  const session={id:"session-concurrent-success",total:"12.10",status:"COVERED",allocations:[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"APPROVED"}]};
  let resolveDiscard!:(value:typeof session&{status:string})=>void;
  const discardResponse=new Promise<typeof session&{status:string}>(resolve=>{resolveDiscard=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard"))return discardResponse;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1));

  const logout=ref.current!.prepareLogout();const close=ref.current!.prepareApplicationClose();
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1);
  resolveDiscard({...session,status:"CANCELLED"});

  await expect(Promise.all([logout,close])).resolves.toEqual(["READY","READY"]);
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1);
 });
 it("shares a failed cleanup result and clears single-flight so a later explicit retry can succeed",async()=>{
  const session={id:"session-concurrent-failure",total:"12.10",status:"COMPENSATION_REQUIRED",allocations:[]};
  let rejectDiscard!:(reason:Error)=>void;let requests=0;
  const firstResponse=new Promise<never>((_resolve,reject)=>{rejectDiscard=reject;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){requests++;return requests===1?firstResponse:{...session,status:"CANCELLED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(requests).toBe(1));

  const logout=ref.current!.prepareLogout();const close=ref.current!.prepareApplicationClose();
  rejectDiscard(new Error("offline"));

  await expect(Promise.all([logout,close])).resolves.toEqual(["BLOCKED","BLOCKED"]);
  expect(requests).toBe(1);
  await expect(ref.current!.prepareLogout()).resolves.toBe("READY");
  expect(requests).toBe(2);
 });
 it.each([
  ["empty",[]],
  ["declined",[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"DECLINED"}]],
  ["error",[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"ERROR"}]],
  ["cancelled",[{id:"a",idempotencyKey:"a",kind:"INTEGRATED_CARD" as const,amount:"12.10",status:"CANCELLED"}]],
 ])("discards a stale AUTO_CANCEL %s session on entry and clears its lock",async(label,allocations)=>{
  const session={id:`session-stale-auto-${label}`,total:"12.10",status:"COLLECTING",allocations};
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){expect(options?.body).toEqual({reason:"sale_entry_cleanup"});return {...session,status:"CANCELLED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const onLockedChange=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onLockedChange,onFinalized:vi.fn()}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1));
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));
  expect(sessionStorage.getItem("tpverp.payment-session.01")).toBeNull();
 });
 it("does not duplicate rejected entry cleanup after a real unmount and remount",async()=>{
  const session={id:"session-stale-live-remount",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard"))throw new ApiError("terminal live",409);
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const first=render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1));
  first.unmount();
  render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/active")).toHaveLength(2));
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1);
  expect(sessionStorage.getItem("tpverp.payment-session.01")).toBe(session.id);
 });
 it("reconciles a CANCELLED entry cleanup with a replacement mount without deleting newer recovery storage",async()=>{
  const session={id:"session-entry-remount-cancelled",total:"12.10",status:"COLLECTING",allocations:[]};
  let resolveDiscard!:(value:typeof session&{status:string})=>void;
  const discardResponse=new Promise<typeof session&{status:string}>(resolve=>{resolveDiscard=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard"))return discardResponse;
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const first=render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1));
  first.unmount();
  const onLockedChange=vi.fn();
  render(createElement(SalePaymentCheckout,{...props,onLockedChange}));
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(true,1210));
  sessionStorage.setItem("tpverp.payment-session.01","newer-session");
  localStorage.setItem("tpverp.payment-session.01.allocation-attempt",JSON.stringify({sessionId:"newer-session",allocationId:"newer-allocation",input:{}}));

  resolveDiscard({...session,status:"CANCELLED"});

  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1);
  expect(sessionStorage.getItem("tpverp.payment-session.01")).toBe("newer-session");
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toContain("newer-session");
 });
 it("keeps the replacement mount locked when shared entry cleanup is not terminal",async()=>{
  const session={id:"session-entry-remount-live",total:"12.10",status:"COLLECTING",allocations:[]};
  let resolveDiscard!:(value:typeof session)=>void;
  const discardResponse=new Promise<typeof session>(resolve=>{resolveDiscard=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard"))return discardResponse;
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const first=render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1));
  first.unmount();
  const onLockedChange=vi.fn();
  render(createElement(SalePaymentCheckout,{...props,onLockedChange}));
  await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(true,1210));

  resolveDiscard(session);

  await act(async()=>{await discardResponse;});
  expect(onLockedChange).toHaveBeenLastCalledWith(true,1210);
  expect(apiRequestMock.mock.calls.filter(([path])=>path.endsWith("/simulator-discard"))).toHaveLength(1);
 });
 it("evicts a settled non-terminal cleanup so a later mount can reconcile evolved backend state",async()=>{
  const session={id:"session-entry-evolves",total:"12.10",status:"COLLECTING",allocations:[]};let discardCalls=0;
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){discardCalls++;return discardCalls===1?session:{...session,status:"CANCELLED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const first=render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(discardCalls).toBe(1));await waitFor(()=>expect(entryCleanupRegistrySizeForTest()).toBe(0));first.unmount();
  const onLockedChange=vi.fn();render(createElement(SalePaymentCheckout,{...props,onLockedChange}));
  await waitFor(()=>expect(discardCalls).toBe(2));await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));
  expect(entryCleanupRegistrySizeForTest()).toBe(0);
 });
 it("evicts a settled cleanup error so a later mount retries",async()=>{
  const session={id:"session-entry-error-retry",total:"12.10",status:"COLLECTING",allocations:[]};let discardCalls=0;
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path.endsWith("/simulator-discard")){discardCalls++;if(discardCalls===1)throw new Error("offline");return {...session,status:"CANCELLED"};}
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const first=render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(discardCalls).toBe(1));await waitFor(()=>expect(entryCleanupRegistrySizeForTest()).toBe(0));first.unmount();
  const onLockedChange=vi.fn();render(createElement(SalePaymentCheckout,{...props,onLockedChange}));
  await waitFor(()=>expect(discardCalls).toBe(2));await waitFor(()=>expect(onLockedChange).toHaveBeenLastCalledWith(false,undefined));
  expect(entryCleanupRegistrySizeForTest()).toBe(0);
 });
 it("translates shutdown and simulator cleanup feedback in every supported locale",()=>{
  for(const locale of ["es","en","zh"] as const){expect(createTranslator(locale)("payment.pending.shutdownBlocked")).not.toBe("payment.pending.shutdownBlocked");expect(createTranslator(locale)("payment.pending.simulatorCleanupError")).not.toBe("payment.pending.simulatorCleanupError");}
 });
 it("selects individual actions when there is no exceptional session",()=>{
  expect(checkoutPresentation(null)).toBe("INDIVIDUAL_ACTIONS");
  expect(checkoutPresentation("FINALIZED")).toBe("INDIVIDUAL_ACTIONS");
 });
 it("selects recovery controls for an active collecting session",()=>{expect(checkoutPresentation("COLLECTING")).toBe("RECOVERY");});
 it("returns safely declined and errored collecting sessions to individual actions",()=>{
  expect(checkoutPresentation("COLLECTING",["DECLINED"])).toBe("INDIVIDUAL_ACTIONS");
  expect(checkoutPresentation("COLLECTING",["ERROR"])).toBe("INDIVIDUAL_ACTIONS");
 });
 it("selects finalization retry controls for a covered session",()=>{expect(checkoutPresentation("COVERED")).toBe("FINALIZE_RETRY");});
 it("selects compensation controls when administrative recovery is required",()=>{expect(checkoutPresentation("COMPENSATION_REQUIRED")).toBe("COMPENSATION");});
 it("renders only individual actions for an ordinary checkout",async()=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:["CUSTOMER_RECEIVABLES_CREATE"],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(screen.getByRole("button",{name:/Efectivo/})).toBeVisible();
  expect(screen.getByRole("button",{name:/Tarjeta/})).toBeVisible();
  expect(screen.getByRole("button",{name:/Pendiente cliente/})).toBeEnabled();
  expect(screen.queryByRole("button",{name:"Cancelar sesión de cobro"})).not.toBeInTheDocument();
 });
 it("keeps the pending action disabled and ignores F12 without receivables-create permission",async()=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   throw new Error(`unexpected request ${path}`);
  });
  const ref=createRef<SalePaymentCheckoutHandle>();
  const onPending=vi.fn();
  render(createElement(SalePaymentCheckout,{ref,locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onPending,onFinalized:vi.fn()}));

  expect(screen.getByRole("button",{name:/Pendiente cliente/})).toBeDisabled();
  act(()=>ref.current?.triggerPending());

  expect(onPending).not.toHaveBeenCalled();
 });
 it.each([
  ["COLLECTING","Cancelar sesión de cobro"],
  ["COVERED","Finalizar venta"],
  ["COMPENSATION_REQUIRED","Registrar resolución administrativa"],
 ])("renders exceptional %s controls without ordinary actions",async(status,control)=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return {id:`session-${status}`,total:"12.10",status,allocations:[]};
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:["ADMIN"],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(await screen.findByRole("button",{name:control})).toBeVisible();
  expect(screen.queryByRole("button",{name:/F10/})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:/F12/})).not.toBeInTheDocument();
 });
 it("focuses and traps the manual-card dialog, closes on Escape, and restores the card trigger",async()=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  card.focus();
  fireEvent.click(card);
  const dialog=screen.getByRole("dialog",{name:"Cobro con tarjeta manual"});
  const reference=within(dialog).getByRole("textbox");
  expect(reference).toHaveFocus();
  const cancel=within(dialog).getByRole("button",{name:"Cancelar"});
  cancel.focus();
  fireEvent.keyDown(cancel,{key:"Tab"});
  expect(reference).toHaveFocus();
  fireEvent.keyDown(dialog,{key:"Escape"});
  expect(screen.queryByRole("dialog",{name:"Cobro con tarjeta manual"})).not.toBeInTheDocument();
 expect(card).toHaveFocus();
 });
 it("keeps delegated manual-card credentials out of the durable allocation attempt",async()=>{
  const session={id:"session-manual-card-auth",total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationBody:Record<string,unknown>|undefined;
  let persistedAttemptAtRequest:string|null=null;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:Record<string,unknown>})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-manual-card-auth/allocations"){
    allocationBody=options?.body;
    persistedAttemptAtRequest=localStorage.getItem("tpverp.payment-session.01.allocation-attempt");
    return {...session,allocations:[{
     id:String(options?.body?.allocationId),
     idempotencyKey:String(options?.body?.allocationId),
     kind:"MANUAL_CARD",
     amount:"12.10",
     status:"APPROVED",
    }]};
   }
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{
   locale:"es",
   totalCents:1210,
   sale:{customerId:null,lines:[]},
   permissions:[],
   terminal:{storeName:"Tienda",terminalCode:"01"},
   manualCardPaymentAuthorization:{
    mode:"DELEGATED",
    requireUsername:true,
    requirePassword:true,
   },
   onFinalized:vi.fn(),
  }));

  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  const referenceDialog=screen.getByRole("dialog",{name:"Cobro con tarjeta manual"});
  fireEvent.change(within(referenceDialog).getByRole("textbox"),{target:{value:"DOC-1"}});
  fireEvent.click(within(referenceDialog).getByRole("button",{name:"Confirmar"}));

  const authorizationDialog=screen.getByRole("dialog",{name:"Autorización de la venta"});
  const manualCardAuthorization=within(authorizationDialog).getByRole(
   "group",
   {name:"Autorizar pago con tarjeta manual"},
  );
  fireEvent.change(within(manualCardAuthorization).getByLabelText("Usuario autorizador"),{target:{value:"ENCARGADO"}});
  fireEvent.change(within(manualCardAuthorization).getByLabelText("Contraseña del autorizador"),{target:{value:"secret-123"}});
  fireEvent.click(within(authorizationDialog).getByRole("button",{name:"Confirmar y continuar"}));

  await waitFor(()=>expect(allocationBody).toBeDefined());
  expect(allocationBody).toMatchObject({
   kind:"MANUAL_CARD",
   reference:"DOC-1",
   operationAuthorization:{
    authorizerUsername:"ENCARGADO",
    authorizerPassword:"secret-123",
   },
  });
  expect(persistedAttemptAtRequest).toContain("MANUAL_CARD");
  expect(persistedAttemptAtRequest).not.toContain("ENCARGADO");
  expect(persistedAttemptAtRequest).not.toContain("secret-123");
  expect(allocationRecoveryInput({
   kind:"MANUAL_CARD",
   amountCents:1210,
   reference:"DOC-1",
  })).toEqual({kind:"MANUAL_CARD",amountCents:1210,provider:undefined});
 });
 it("offers only query, manage, and cancel for an uncertain full-ticket recovery",async()=>{
  const uncertain={id:"session-uncertain-recovery",total:"12.10",status:"COLLECTING",allocations:[{id:"allocation-timeout",idempotencyKey:"allocation-timeout",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-timeout",status:"TIMEOUT"}]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return uncertain;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(await screen.findByRole("heading",{name:"Cobro pendiente"})).toBeVisible();
  expect(await screen.findByRole("button",{name:"Consultar estado"})).toBeVisible();
  expect(screen.getByRole("button",{name:"Gestionar operación"})).toBeVisible();
  expect(screen.getByRole("button",{name:"Cancelar sesión de cobro"})).toBeVisible();
  expect(screen.queryByRole("button",{name:"Efectivo"})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"Tarjeta manual"})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"GLOBAL_PAYMENTS"})).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox",{name:/Importe/})).not.toBeInTheDocument();
  expect(screen.queryByText("Cobro dividido")).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"Iniciar cobro dividido"})).not.toBeInTheDocument();
 });
 it("renders a safe declined session as a fresh individual checkout",async()=>{
  const declined={id:"session-declined",total:"12.10",status:"COLLECTING",allocations:[{id:"allocation-declined",idempotencyKey:"allocation-declined",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-declined",status:"DECLINED"}]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return declined;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:["CUSTOMER_RECEIVABLES_CREATE"],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(await screen.findByRole("button",{name:/AvPág/})).toBeVisible();
  expect(screen.getByRole("button",{name:/F11/})).toBeVisible();
  expect(screen.getByRole("button",{name:/F12/})).toBeEnabled();
  expect(screen.queryByRole("button",{name:"Cancelar sesión de cobro"})).not.toBeInTheDocument();
 });
 it("delegates the cash action exactly once when onCash is supplied",async()=>{
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   throw new Error(`unexpected request ${path}`);
  });
  const onCash=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onCash,onFinalized:vi.fn()}));

  fireEvent.click(screen.getByRole("button",{name:/Efectivo.*AvPág/}));

  expect(onCash).toHaveBeenCalledOnce();
  expect(screen.queryByRole("dialog",{name:"Cobro en efectivo"})).not.toBeInTheDocument();
  expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions")).toHaveLength(0);
 });
 it("starts a full-total integrated card allocation with the configured provider",async()=>{
  const session={id:"session-card",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-card/allocations")return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS"}]};
   if(path==="/pos/payment-sessions/session-card/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-CARD",printTicket:printTicket("T-CARD")};
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  await waitFor(()=>expect(screen.getByRole("button",{name:/Tarjeta/})).toBeEnabled());
  fireEvent.click(screen.getByRole("button",{name:/Tarjeta/}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-card/allocations")).toHaveLength(1));
  expect(apiRequestMock.mock.calls.find(([path])=>path==="/pos/payment-sessions/session-card/allocations")?.[1].body).toMatchObject({kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS"});
 });

 it("releases the card guard after a declined allocation so F11 can retry",async()=>{
  const session={id:"session-card-declined-retry",total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationCalls=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-card-declined-retry/allocations"){
    allocationCalls+=1;
    const id=(options?.body as {allocationId:string}).allocationId;
    return {...session,allocations:[{id,idempotencyKey:id,status:"DECLINED",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS"}]};
   }
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const firstCard=await screen.findByRole("button",{name:/Tarjeta.*F11/});
  await waitFor(()=>expect(firstCard).toBeEnabled());
  fireEvent.click(firstCard);
  await waitFor(()=>expect(allocationCalls).toBe(1));
  const retryCard=screen.getByRole("button",{name:/Tarjeta.*F11/});
  fireEvent.click(retryCard);
  await waitFor(()=>expect(allocationCalls).toBe(2));
 });

 it.each(["DECLINED","ERROR"])("releases the cash guard after a successful %s response so cash can retry",async(status)=>{
  const session={id:`session-cash-${status.toLowerCase()}-retry`,total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationCalls=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path===`/pos/payment-sessions/${session.id}/allocations`){
    allocationCalls+=1;
    const id=(options?.body as {allocationId:string}).allocationId;
    return {...session,allocations:[{id,idempotencyKey:id,status,kind:"CASH",amount:"12.10"}]};
   }
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  fireEvent.click(await screen.findByRole("button",{name:/Efectivo.*AvPág/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(allocationCalls).toBe(1));
  fireEvent.click(screen.getByRole("button",{name:/Efectivo.*AvPág/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(allocationCalls).toBe(2));
 });

 it.each(["DECLINED","ERROR"])("releases the card guard when query resolves an uncertain allocation to %s",async(status)=>{
  const session={id:`session-card-query-${status.toLowerCase()}`,total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationCalls=0;
  let allocationId="";
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path===`/pos/payment-sessions/${session.id}/allocations`){
    allocationCalls+=1;
    allocationId=(options?.body as {allocationId:string}).allocationId;
    return {...session,allocations:[{id:allocationId,idempotencyKey:allocationId,status:"TIMEOUT",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-card-query"}]};
   }
   if(path===`/pos/payment-sessions/${session.id}/allocations/operation-card-query/query`)return {...session,allocations:[{id:allocationId,idempotencyKey:allocationId,status,kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-card-query"}]};
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const cardButton=await screen.findByRole("button",{name:/Tarjeta.*F11/});
  await waitFor(()=>expect(cardButton).toBeEnabled());
  fireEvent.click(cardButton);
  await waitFor(()=>expect(screen.getByRole("button",{name:"Consultar estado"})).toBeEnabled());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Consultar estado"}));
  const retry=await screen.findByRole("button",{name:/Tarjeta.*F11/});
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
  fireEvent.click(retry);
  await waitFor(()=>expect(allocationCalls).toBe(2));
 });

 it.each(["DECLINED","ERROR"])("cleans the cash dialog and guard when query resolves an uncertain allocation to %s",async(status)=>{
  const session={id:`session-cash-query-${status.toLowerCase()}`,total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationCalls=0;
  let allocationId="";
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path===`/pos/payment-sessions/${session.id}/allocations`){
    allocationCalls+=1;
    allocationId=(options?.body as {allocationId:string}).allocationId;
    return {...session,allocations:[{id:allocationId,idempotencyKey:allocationId,status:"TIMEOUT",kind:"CASH",amount:"12.10",operationId:"operation-cash-query"}]};
   }
   if(path===`/pos/payment-sessions/${session.id}/allocations/operation-cash-query/query`)return {...session,allocations:[{id:allocationId,idempotencyKey:allocationId,status,kind:"CASH",amount:"12.10",operationId:"operation-cash-query"}]};
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  fireEvent.click(await screen.findByRole("button",{name:/Efectivo.*AvPág/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:"Consultar estado"})).toBeEnabled());
  expect(screen.getByRole("dialog",{name:"Cobro en efectivo"})).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Consultar estado"}));
  await waitFor(()=>expect(screen.queryByRole("dialog",{name:"Cobro en efectivo"})).not.toBeInTheDocument());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:/Efectivo.*AvPág/}));
  fireEvent.click(screen.getByRole("button",{name:/50/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(allocationCalls).toBe(2));
 });

 it.each(["PENDING","TIMEOUT"])("keeps recovery metadata and blocks a new charge when query remains %s",async(status)=>{
  const session={id:`session-card-query-${status.toLowerCase()}`,total:"12.10",status:"COLLECTING",allocations:[]};
  let allocationId="";
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path===`/pos/payment-sessions/${session.id}/allocations`){allocationId=(options?.body as {allocationId:string}).allocationId;return {...session,allocations:[{id:allocationId,idempotencyKey:allocationId,status:"TIMEOUT",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-query-uncertain"}]};}
   if(path===`/pos/payment-sessions/${session.id}/allocations/operation-query-uncertain/query`)return {...session,allocations:[{id:allocationId,idempotencyKey:allocationId,status,kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-query-uncertain"}]};
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const cardButton=await screen.findByRole("button",{name:/Tarjeta.*F11/});
  await waitFor(()=>expect(cardButton).toBeEnabled());
  fireEvent.click(cardButton);
  await waitFor(()=>expect(screen.getByRole("button",{name:"Consultar estado"})).toBeEnabled());
  fireEvent.click(screen.getByRole("button",{name:"Consultar estado"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:"Consultar estado"})).toBeEnabled());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  expect(screen.queryByRole("button",{name:/Tarjeta.*F11/})).not.toBeInTheDocument();
 });

 it("synchronously ignores a second integrated card click",async()=>{
  const session={id:"session-card-guard",total:"12.10",status:"COLLECTING",allocations:[]};
  let releaseSession!:(value:typeof session)=>void;
  const pendingSession=new Promise<typeof session>(resolve=>{releaseSession=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return pendingSession;
   if(path==="/pos/payment-sessions/session-card-guard/allocations")return session;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  act(()=>{card.click();card.click();});
  expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions")).toHaveLength(1);
  releaseSession(session);
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-card-guard/allocations")).toHaveLength(1));
 });

 it("does not create a second charge after an uncertain integrated card outcome",async()=>{
  const session={id:"session-card-uncertain",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-card-uncertain/allocations")throw new ApiError("timeout",500);
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("timeout"));
  fireEvent.click(card);
  expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-card-uncertain/allocations")).toHaveLength(1);
  expect(JSON.parse(localStorage.getItem("tpverp.payment-session.01.allocation-attempt") ?? "null")).toMatchObject({sessionId:"session-card-uncertain",input:{kind:"INTEGRATED_CARD",amountCents:1210,provider:"GLOBAL_PAYMENTS"}});
 });

 it("opens direct manual card entry and submits its trimmed reference",async()=>{
  const session={id:"session-manual",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-manual/allocations")return session;
   throw new Error(`unexpected request ${path} ${JSON.stringify(options)}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  const dialog=screen.getByRole("dialog",{name:"Cobro con tarjeta manual"});
  const confirm=within(dialog).getByRole("button",{name:"Confirmar"});
  fireEvent.change(within(dialog).getByRole("textbox"),{target:{value:"   "}});
  expect(confirm).toBeDisabled();
  fireEvent.change(within(dialog).getByRole("textbox"),{target:{value:" REF-1 "}});
  fireEvent.click(confirm);
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-manual/allocations")).toHaveLength(1));
  expect(apiRequestMock.mock.calls.find(([path])=>path==="/pos/payment-sessions/session-manual/allocations")?.[1].body).toMatchObject({kind:"MANUAL_CARD",amount:"12.10",reference:"REF-1"});
 });

 it("never persists a manual card reference while retaining its stable allocation id",async()=>{
  const session={id:"session-manual-private",total:"12.10",status:"COLLECTING",allocations:[]};
  let releaseAllocation!:(value:typeof session)=>void;
  const pendingAllocation=new Promise<typeof session>(resolve=>{releaseAllocation=resolve;});
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-manual-private/allocations")return pendingAllocation;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  fireEvent.change(screen.getByRole("textbox",{name:"Referencia obligatoria"}),{target:{value:" SECRET-REF "}});
  fireEvent.click(screen.getByRole("button",{name:"Confirmar"}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-manual-private/allocations")).toHaveLength(1));
  const persisted=localStorage.getItem("tpverp.payment-session.01.allocation-attempt") ?? "";
  expect(persisted).not.toContain("SECRET-REF");
  expect(JSON.parse(persisted)).toMatchObject({sessionId:"session-manual-private",allocationId:expect.any(String),input:{kind:"MANUAL_CARD",amountCents:1210}});
  releaseAllocation(session);
 });

 it("retains allocation recovery metadata across finalization failure and reload",async()=>{
  const collecting={id:"session-finalize-reload",total:"12.10",status:"COLLECTING",allocations:[]};
  const covered={...collecting,status:"COVERED",allocations:[{id:"allocation-stable",idempotencyKey:"allocation-stable",status:"APPROVED",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS"}]};
  let finalizeAttempts=0;
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return finalizeAttempts?covered:null;
   if(path==="/pos/payment-sessions")return collecting;
   if(path==="/pos/payment-sessions/session-finalize-reload/allocations")return covered;
   if(path==="/pos/payment-sessions/session-finalize-reload/finalize"){
    finalizeAttempts+=1;
    if(finalizeAttempts===1)throw new ApiError("finalize unavailable",500);
    return {...covered,status:"FINALIZED",ticketNumber:"T-RECOVERED",printTicket:printTicket("T-RECOVERED")};
   }
   throw new Error(`unexpected request ${path}`);
  });
  const props={locale:"es" as const,totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[] as never[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()};
  const first=render(createElement(SalePaymentCheckout,props));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("finalize unavailable"));
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  first.unmount();
  render(createElement(SalePaymentCheckout,props));
  await waitFor(()=>expect(screen.getByRole("button",{name:"Finalizar venta"})).toBeEnabled());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Finalizar venta"}));
  await waitFor(()=>expect(props.onFinalized).toHaveBeenCalledWith(printTicket("T-RECOVERED"),{kind:"CARD",totalCents:1210}));
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
 });

 it("discards an uncertain simulated card payment so the operator can choose another method",async()=>{
  const first={id:"session-cancel-card-1",total:"12.10",status:"COLLECTING",allocations:[]};
  const second={id:"session-cancel-card-2",total:"12.10",status:"COLLECTING",allocations:[]};
  let creations=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return ++creations===1?first:second;
   if(path==="/pos/payment-sessions/session-cancel-card-1/allocations"){
    const id=(options?.body as {allocationId:string}).allocationId;
    return {...first,allocations:[{id,idempotencyKey:id,status:"TIMEOUT",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"op-1"}]};
   }
   if(path==="/pos/payment-sessions/session-cancel-card-1/cancel")return {...first,status:"COMPENSATION_REQUIRED",allocations:[{id:"card-timeout",idempotencyKey:"card-timeout",status:"TIMEOUT",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"op-1"}]};
   if(path==="/pos/payment-sessions/session-cancel-card-1/simulator-discard"){
    expect(options?.body).toEqual({reason:"payment_method_change"});
    return {...first,status:"CANCELLED"};
   }
   if(path==="/pos/payment-sessions/session-cancel-card-2/allocations")return second;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  await waitFor(()=>expect(screen.getByRole("button",{name:"Cancelar sesión de cobro"})).toBeEnabled());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Cancelar sesión de cobro"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:/Tarjeta/})).toBeEnabled());
  expect(screen.getByRole("button",{name:/Efectivo/})).toBeEnabled();
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:/Tarjeta/}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-cancel-card-2/allocations")).toHaveLength(1));
 });

 it("clears card recovery state after compensation acknowledgement so a later card checkout can start",async()=>{
 const first={id:"session-ack-card-1",total:"12.10",status:"COLLECTING",allocations:[]};
 const second={id:"session-ack-card-2",total:"12.10",status:"COLLECTING",allocations:[]};
 let creations=0;
  let acknowledgementBody:unknown;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return ++creations===1?first:second;
   if(path==="/pos/payment-sessions/session-ack-card-1/allocations"){
    const id=(options?.body as {allocationId:string}).allocationId;
    return {...first,status:"COMPENSATION_REQUIRED",allocations:[{id,idempotencyKey:id,status:"TIMEOUT",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"op-ack"}]};
   }
   if(path==="/pos/payment-sessions/session-ack-card-1/compensation-ack"){
    acknowledgementBody=options?.body;
    return {...first,status:"CANCELLED"};
   }
   if(path==="/pos/payment-sessions/session-ack-card-2/allocations")return second;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{
   locale:"es",
   totalCents:1210,
   sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},
   permissions:[],
   terminal:{storeName:"Tienda",terminalCode:"01"},
   paymentCompensationAuthorization:{mode:"DELEGATED",requireUsername:true,requirePassword:true},
   onFinalized:vi.fn(),
  }));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  await waitFor(()=>expect(screen.getByRole("button",{name:"Registrar resolución administrativa"})).toBeEnabled());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Registrar resolución administrativa"}));
  const dialog=screen.getByRole("dialog",{name:"Resolución administrativa"});
  const textboxes=within(dialog).getAllByRole("textbox");
  fireEvent.change(textboxes[0],{target:{value:" Resuelta "}});
  fireEvent.change(textboxes[1],{target:{value:"supervisor"}});
  fireEvent.change(within(dialog).getByLabelText(/Contrase/i),{target:{value:"secreto"}});
  fireEvent.click(within(dialog).getByRole("button",{name:"Confirmar"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:/Tarjeta/})).toBeEnabled());
  expect(acknowledgementBody).toEqual({
   note:"Resuelta",
   authorizerUsername:"supervisor",
   authorizerPassword:"secreto",
  });
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:/Tarjeta/}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-ack-card-2/allocations")).toHaveLength(1));
 });

 it("synchronously ignores a second cash confirmation before React disables the dialog",async()=>{
  const session={id:"session-guard",total:"12.10",status:"COLLECTING",allocations:[]};
  let releaseSession!:(value:typeof session)=>void;
  const pendingSession=new Promise<typeof session>(resolve=>{releaseSession=resolve;});
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return pendingSession;
   if(path==="/pos/payment-sessions/session-guard/allocations")return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"CASH",amount:"12.10"}]};
   if(path==="/pos/payment-sessions/session-guard/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-GUARD",printTicket:printTicket("T-GUARD"),allocations:[{id:"cash-guard",idempotencyKey:"cash-guard",status:"APPROVED",kind:"CASH",amount:"12.10"}]};
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

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith(printTicket("T-GUARD"),{kind:"CASH",totalCents:1210,receivedCents:2000}));
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
   if(path==="/pos/payment-sessions/session-safe/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-MANUAL",printTicket:printTicket("T-MANUAL"),allocations:[{id:"card-safe",idempotencyKey:"card-safe",status:"APPROVED",kind:"MANUAL_CARD",amount:"12.10"}]};
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));
  fireEvent.click(screen.getByRole("button",{name:/Efectivo/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(screen.getAllByText("cash rejected safely")).not.toHaveLength(0));
  fireEvent.click(screen.getByRole("button",{name:"Cancelar"}));

  fireEvent.click(screen.getByRole("button",{name:/Tarjeta.*F11/}));
  const manualDialog=screen.getByRole("dialog",{name:"Cobro con tarjeta manual"});
  fireEvent.change(within(manualDialog).getByRole("textbox"),{target:{value:"REF-1"}});
  fireEvent.click(within(manualDialog).getByRole("button",{name:"Confirmar"}));

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith(printTicket("T-MANUAL"),{kind:"CARD",totalCents:1210}));
 });

 it("opens test cash for a covered checkout and leaves finalization explicit", async () => {
  const session = {
   id: "session-test-cash",
   total: "12.10",
   status: "COVERED",
   allocations: [{
    id: "cash-1",
    idempotencyKey: "cash-1",
    kind: "CASH" as const,
    amount: "12.10",
    status: "APPROVED",
   }],
  };
  let finalizeCalls = 0;
  apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown; token?: string }) => {
   if (path === "/terminal-configuration/payment") return {
    rules: { cardManualEnabled: true, integratedCardEnabled: false },
    providerDescriptors: [],
    configuration: { provider: "", enabled: false },
   };
   if (path === "/pos/payment-sessions/active") return session;
   if (path === "/pos/payment-sessions/session-test-cash/finalize") {
    finalizeCalls += 1;
    throw new ApiError("No hay una sesión de caja abierta", 409);
   }
   if (path === "/cash/sessions/open") {
    expect(options?.body).toEqual({ terminalId: "terminal-1" });
    expect(options?.token).toBe("dev-access-token");
    return { id: "cash-session-1", status: "ABIERTA", openingFund: "0.00" };
   }
   throw new Error(`unexpected request ${path}`);
  });

  render(createElement(SalePaymentCheckout, {
   locale: "es",
   totalCents: 1210,
   sale: { customerId: null, lines: [{ productId: "p-1", quantity: 1, discount: 0 }] },
   permissions: ["ADMIN"],
   terminal: { storeName: "Tienda", terminalCode: "01", terminalId: "terminal-1" },
   testCashEnabled: true,
   token: "dev-access-token",
   onFinalized: vi.fn(),
  }));

  const finalize = await screen.findByRole("button", { name: "Finalizar venta" });
  fireEvent.click(finalize);
  const openTestCash = await screen.findByRole("button", { name: "Abrir caja de prueba" });
  fireEvent.click(openTestCash);

  expect(await screen.findByRole("status")).toHaveTextContent(
   "Caja de prueba abierta. Pulse Finalizar venta.",
  );
  expect(finalizeCalls).toBe(1);
  expect(apiRequestMock.mock.calls.filter(([path]) =>
   path === "/cash/sessions/open")).toHaveLength(1);
  expect(apiRequestMock.mock.calls.filter(([path]) =>
   path.includes("/allocations"))).toHaveLength(0);
 });

 it("keeps the test cash offer retryable when opening fails", async () => {
  const session = {id:"session-open-retry",total:"12.10",status:"COVERED",allocations:[{id:"cash",idempotencyKey:"cash",kind:"CASH",amount:"12.10",status:"APPROVED"}]};
  let openCalls = 0;
  apiRequestMock.mockImplementation(async(path:string) => {
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path==="/pos/payment-sessions/session-open-retry/finalize")throw new ApiError("No hay una sesión de caja abierta",409);
   if(path==="/cash/sessions/open"){
    openCalls += 1;
    if(openCalls===1)throw new ApiError("No se pudo abrir",503);
    return {id:"cash-session-retry",status:"ABIERTA",openingFund:"0.00"};
   }
   throw new Error(`unexpected request ${path}`);
  });

  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:["ADMIN"],terminal:{storeName:"Tienda",terminalCode:"01",terminalId:"terminal-1"},testCashEnabled:true,onFinalized:vi.fn()}));
  fireEvent.click(await screen.findByRole("button",{name:"Finalizar venta"}));
  fireEvent.click(await screen.findByRole("button",{name:"Abrir caja de prueba"}));
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("No se pudo abrir"));
  fireEvent.click(screen.getByRole("button",{name:"Abrir caja de prueba"}));
  expect(await screen.findByRole("status")).toHaveTextContent("Caja de prueba abierta");
  expect(openCalls).toBe(2);
 });

 it("hides the test cash offer after a later unrelated finalization error", async () => {
  const session = {id:"session-finalize-errors",total:"12.10",status:"COVERED",allocations:[{id:"cash",idempotencyKey:"cash",kind:"CASH",amount:"12.10",status:"APPROVED"}]};
  let finalizeCalls = 0;
  apiRequestMock.mockImplementation(async(path:string) => {
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path==="/pos/payment-sessions/session-finalize-errors/finalize"){
    finalizeCalls += 1;
    throw finalizeCalls===1
     ? new ApiError("No hay una sesión de caja abierta",409)
     : new ApiError("Ticket bloqueado por otra causa",409);
   }
   throw new Error(`unexpected request ${path}`);
  });

  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:["ADMIN"],terminal:{storeName:"Tienda",terminalCode:"01",terminalId:"terminal-1"},testCashEnabled:true,onFinalized:vi.fn()}));
  fireEvent.click(await screen.findByRole("button",{name:"Finalizar venta"}));
  expect(await screen.findByRole("button",{name:"Abrir caja de prueba"})).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Finalizar venta"}));
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("Ticket bloqueado"));
  expect(screen.queryByRole("button",{name:"Abrir caja de prueba"})).not.toBeInTheDocument();
 });

 it("offers test cash inside the active cash dialog and retries finalization explicitly", async () => {
  const session = { id: "session-modal-cash", total: "12.10", status: "COLLECTING", allocations: [] };
  let finalizeCalls = 0;
  apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown }) => {
   if (path === "/terminal-configuration/payment") return {
    rules: { cardManualEnabled: true, integratedCardEnabled: false },
    providerDescriptors: [],
    configuration: { provider: "", enabled: false },
   };
   if (path === "/pos/payment-sessions/active") return null;
   if (path === "/pos/payment-sessions") return session;
   if (path === "/pos/payment-sessions/session-modal-cash/allocations") return {
    ...session,
    status: "COVERED",
    allocations: [{
     id: (options?.body as { allocationId: string }).allocationId,
     idempotencyKey: "cash-modal",
     kind: "CASH",
     amount: "12.10",
     status: "APPROVED",
    }],
   };
   if (path === "/pos/payment-sessions/session-modal-cash/finalize") {
    finalizeCalls += 1;
    if (finalizeCalls === 1) throw new ApiError("No hay una sesión de caja abierta", 409);
    return { ...session, status: "FINALIZED", ticketNumber: "T-MODAL-CASH", printTicket: printTicket("T-MODAL-CASH"), allocations: [{id:"cash-modal",idempotencyKey:"cash-modal",kind:"CASH",amount:"12.10",status:"APPROVED"}] };
   }
   if (path === "/cash/sessions/open") return { id: "cash-session-modal", status: "ABIERTA", openingFund: "0.00" };
   throw new Error(`unexpected request ${path}`);
  });

  const onFinalized = vi.fn();
  render(createElement(SalePaymentCheckout, {
   locale: "es",
   totalCents: 1210,
   sale: { customerId: null, lines: [{ productId: "p-1", quantity: 1, discount: 0 }] },
   permissions: ["ADMIN"],
   terminal: { storeName: "Tienda", terminalCode: "01", terminalId: "terminal-1" },
   testCashEnabled: true,
   onFinalized,
  }));

  fireEvent.click(await screen.findByRole("button", { name: /Efectivo/ }));
  const dialog = screen.getByRole("dialog", { name: "Cobro en efectivo" });
  fireEvent.click(within(dialog).getByRole("button", { name: "Exacto" }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Confirmar cobro" }));

  const openTestCash = await within(dialog).findByRole("button", { name: "Abrir caja de prueba" });
  fireEvent.click(openTestCash);

  expect(await within(dialog).findByRole("status")).toHaveTextContent("Caja de prueba abierta. Pulse Confirmar cobro.");
  expect(finalizeCalls).toBe(1);
  fireEvent.click(within(dialog).getByRole("button", { name: "Confirmar cobro" }));

  await waitFor(() => expect(onFinalized).toHaveBeenCalledWith(printTicket("T-MODAL-CASH"), {
   kind: "CASH",
   totalCents: 1210,
   receivedCents: 1210,
  }));
  expect(apiRequestMock.mock.calls.filter(([path]) => path.includes("/allocations"))).toHaveLength(1);
  expect(apiRequestMock.mock.calls.filter(([path]) => path.endsWith("/finalize"))).toHaveLength(2);
 });

 it("reconciles the active session after an uncertain allocation error mentioning missing cash", async () => {
  const session = { id: "session-uncertain-cash", total: "12.10", status: "COLLECTING", allocations: [] };
  let activeCalls = 0;
  apiRequestMock.mockImplementation(async (path: string) => {
   if (path === "/terminal-configuration/payment") return {
    rules: { cardManualEnabled: true, integratedCardEnabled: false },
    providerDescriptors: [],
    configuration: { provider: "", enabled: false },
   };
   if (path === "/pos/payment-sessions/active") {
    activeCalls += 1;
    return activeCalls === 1 ? null : session;
   }
   if (path === "/pos/payment-sessions") return session;
   if (path === "/pos/payment-sessions/session-uncertain-cash/allocations") {
    throw new ApiError("No hay una sesión de caja abierta", 500);
   }
   throw new Error(`unexpected request ${path}`);
  });

  render(createElement(SalePaymentCheckout, {
   locale: "es",
   totalCents: 1210,
   sale: { customerId: null, lines: [{ productId: "p-1", quantity: 1, discount: 0 }] },
   permissions: ["ADMIN"],
   terminal: { storeName: "Tienda", terminalCode: "01", terminalId: "terminal-1" },
   testCashEnabled: true,
   onFinalized: vi.fn(),
  }));

  fireEvent.click(await screen.findByRole("button", { name: /Efectivo/ }));
  const dialog = screen.getByRole("dialog", { name: "Cobro en efectivo" });
  fireEvent.click(within(dialog).getByRole("button", { name: "Exacto" }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Confirmar cobro" }));

  await waitFor(() => expect(activeCalls).toBe(2));
  expect(apiRequestMock.mock.calls.filter(([path]) => path.includes("/allocations"))).toHaveLength(1);
 });

 it("does not leak opened test cash status or stale errors into the next checkout", async () => {
  const first={id:"session-first",total:"12.10",status:"COVERED",allocations:[{id:"cash-first",idempotencyKey:"cash-first",kind:"CASH",amount:"12.10",status:"APPROVED"}]};
  const second={id:"session-second",total:"12.10",status:"COLLECTING",allocations:[]};
  let finalizeFirstCalls=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return first;
   if(path==="/pos/payment-sessions/session-first/finalize"){
    finalizeFirstCalls+=1;
    if(finalizeFirstCalls===1)throw new ApiError("No hay una sesión de caja abierta",409);
    return {...first,status:"FINALIZED",ticketNumber:"T-FIRST",printTicket:printTicket("T-FIRST")};
   }
   if(path==="/cash/sessions/open")return {id:"cash-session-1",status:"ABIERTA",openingFund:"0.00"};
   if(path==="/pos/payment-sessions")return second;
   if(path==="/pos/payment-sessions/session-second/allocations")return {...second,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,idempotencyKey:"second",kind:"MANUAL_CARD",amount:"12.10",status:"APPROVED"}]};
   if(path==="/pos/payment-sessions/session-second/finalize")throw new ApiError("Segundo cierre rechazado",409);
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:["ADMIN"],terminal:{storeName:"Tienda",terminalCode:"01",terminalId:"terminal-1"},testCashEnabled:true,onFinalized}));

  fireEvent.click(await screen.findByRole("button",{name:"Finalizar venta"}));
  fireEvent.click(await screen.findByRole("button",{name:"Abrir caja de prueba"}));
  expect(await screen.findByRole("status")).toHaveTextContent("Caja de prueba abierta");
  fireEvent.click(screen.getByRole("button",{name:"Finalizar venta"}));
  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith(printTicket("T-FIRST"), {
   kind: "CASH",
   totalCents: 1210,
   receivedCents: 1210,
  }));

  fireEvent.click(screen.getByRole("button",{name:/Tarjeta.*F11/}));
  const dialog=screen.getByRole("dialog",{name:"Cobro con tarjeta manual"});
  fireEvent.change(within(dialog).getByRole("textbox"),{target:{value:"REF-NEXT"}});
  fireEvent.click(within(dialog).getByRole("button",{name:"Confirmar"}));
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("Segundo cierre rechazado"));
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"Abrir caja de prueba"})).not.toBeInTheDocument();
 });

 it("opens the touch cash calculator and submits the total exactly once",async()=>{
  const session={id:"session-1",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-1/allocations")return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"CASH",amount:"12.10"}]};
   if(path==="/pos/payment-sessions/session-1/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-1",printTicket:printTicket("T-1"),allocations:[{id:"cash-1",idempotencyKey:"cash-1",status:"APPROVED",kind:"CASH",amount:"12.10"}]};
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

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith(printTicket("T-1"), {
   kind: "CASH",
   totalCents: 1210,
   receivedCents: 2000,
  }));
  const allocationCalls=apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-1/allocations");
  expect(allocationCalls).toHaveLength(1);
  expect(allocationCalls[0][1].body).toMatchObject({kind:"CASH",amount:"12.10"});
 });
 it.each([
  ["CASH from PaymentAllocationPanel", [
   {id:"cash",idempotencyKey:"cash",kind:"CASH",amount:"20.00",status:"APPROVED"},
   {id:"declined-card",idempotencyKey:"declined-card",kind:"MANUAL_CARD",amount:"1.00",status:"DECLINED"},
  ], {kind:"CASH",totalCents:1210,receivedCents:1210}],
  ["CARD", [{id:"card",idempotencyKey:"card",kind:"MANUAL_CARD",amount:"12.10",status:"APPROVED"}], {kind:"CARD",totalCents:1210}],
  ["MIXED", [
   {id:"cash",idempotencyKey:"cash",kind:"CASH",amount:"5.00",status:"APPROVED"},
   {id:"card",idempotencyKey:"card",kind:"INTEGRATED_CARD",amount:"7.10",status:"APPROVED"},
  ], {kind:"MIXED",totalCents:1210}],
 ] as const)("emits an explicit %s finalization summary from effective allocations", async (_label, allocations, expectedSummary) => {
  const session={id:"session-summary",total:"12.10",status:"COVERED",allocations};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return session;
   if(path==="/pos/payment-sessions/session-summary/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-SUMMARY",printTicket:printTicket("T-SUMMARY")};
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));

  fireEvent.click(await screen.findByRole("button",{name:"Finalizar venta"}));

  await waitFor(()=>expect(onFinalized).toHaveBeenCalledWith(printTicket("T-SUMMARY"),expectedSummary));
 });
 it("keeps the recoverable session when a finalized response omits printTicket", async () => {
  const collecting={id:"session-done-empty",total:"12.10",status:"COLLECTING",allocations:[]};
  const covered={...collecting,status:"COVERED",allocations:[{id:"cash",idempotencyKey:"cash",kind:"CASH",amount:"12.10",status:"APPROVED"}]};
  let activeCalls=0;
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active"){activeCalls+=1;return null;}
   if(path==="/pos/payment-sessions")return collecting;
   if(path==="/pos/payment-sessions/session-done-empty/allocations")return covered;
   if(path==="/pos/payment-sessions/session-done-empty/finalize")return {...collecting,status:"FINALIZED",ticketNumber:"T-DONE-EMPTY",allocations:[]};
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));

  fireEvent.click(await screen.findByRole("button",{name:/Efectivo/}));
  fireEvent.click(screen.getByRole("button",{name:"Exacto"}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));

  await waitFor(()=>expect(screen.getAllByRole("alert")[0]).toHaveTextContent("No se pudo finalizar la venta"));
  expect(onFinalized).not.toHaveBeenCalled();
  expect(sessionStorage.getItem("tpverp.payment-session.01")).toBe("session-done-empty");
  expect(screen.getByRole("button",{name:"Finalizar venta"})).toBeInTheDocument();
  expect(activeCalls).toBe(1);
 });
 it("rejects an incoherent covered session before finalize and keeps checkout safely locked", async () => {
  const covered={id:"session-invalid-covered",total:"12.10",status:"COVERED",allocations:[{id:"cash",idempotencyKey:"cash",kind:"CASH",amount:"12.10",status:"DECLINED"}]};
  let finalizeCalls=0;
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:false},providerDescriptors:[],configuration:{provider:"",enabled:false}};
   if(path==="/pos/payment-sessions/active")return covered;
   if(path==="/pos/payment-sessions/session-invalid-covered/finalize"){finalizeCalls+=1;return {...covered,status:"FINALIZED",ticketNumber:"T-INVALID"};}
   throw new Error(`unexpected request ${path}`);
  });
  const onFinalized=vi.fn();
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized}));

  fireEvent.click(await screen.findByRole("button",{name:"Finalizar venta"}));

  await waitFor(()=>expect(screen.getByRole("alert")).toBeInTheDocument());
  expect(finalizeCalls).toBe(0);
  expect(onFinalized).not.toHaveBeenCalled();
  expect(screen.queryByRole("button",{name:/Efectivo.*AvPág/})).not.toBeInTheDocument();
  expect(screen.getByRole("button",{name:"Finalizar venta"})).toBeInTheDocument();
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
