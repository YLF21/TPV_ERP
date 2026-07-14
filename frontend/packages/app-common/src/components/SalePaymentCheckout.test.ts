// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, describe,expect,it,vi } from "vitest";
import { authorizationPasswordIsEphemeral,canManuallyFinalizePayment,checkoutPresentation,compensationNoteIsEphemeral,compensationGuidanceKey,paymentSessionAfterFinalization,paymentSessionLocksSale,stableAllocationAttempt,allocationFailureIsSafePreEffect } from "./SalePaymentCheckout";
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
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(screen.getByRole("button",{name:/Efectivo/})).toBeVisible();
  expect(screen.getByRole("button",{name:/Tarjeta/})).toBeVisible();
  expect(screen.getByRole("button",{name:/Pendiente cliente/})).toBeDisabled();
  expect(screen.queryByRole("button",{name:"Cancelar sesión de cobro"})).not.toBeInTheDocument();
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
 it("offers only query, manage, and cancel for an uncertain full-ticket recovery",async()=>{
  const uncertain={id:"session-uncertain-recovery",total:"12.10",status:"COLLECTING",allocations:[{id:"allocation-timeout",idempotencyKey:"allocation-timeout",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-timeout",status:"TIMEOUT"}]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return uncertain;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(await screen.findByRole("button",{name:"Consultar estado"})).toBeVisible();
  expect(screen.getByRole("button",{name:"Gestionar operación"})).toBeVisible();
  expect(screen.getByRole("button",{name:"Cancelar sesión de cobro"})).toBeVisible();
  expect(screen.queryByRole("button",{name:"Efectivo"})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"Tarjeta manual"})).not.toBeInTheDocument();
  expect(screen.queryByRole("button",{name:"GLOBAL_PAYMENTS"})).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox",{name:/Importe/})).not.toBeInTheDocument();
 });
 it("renders a safe declined session as a fresh individual checkout",async()=>{
  const declined={id:"session-declined",total:"12.10",status:"COLLECTING",allocations:[{id:"allocation-declined",idempotencyKey:"allocation-declined",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"operation-declined",status:"DECLINED"}]};
  apiRequestMock.mockImplementation(async(path:string)=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return declined;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[]},permissions:[],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  expect(await screen.findByRole("button",{name:/F10/})).toBeVisible();
  expect(screen.getByRole("button",{name:/F11/})).toBeVisible();
  expect(screen.getByRole("button",{name:/F12/})).toBeDisabled();
  expect(screen.queryByRole("button",{name:"Cancelar sesión de cobro"})).not.toBeInTheDocument();
 });
 it("starts a full-total integrated card allocation with the configured provider",async()=>{
  const session={id:"session-card",total:"12.10",status:"COLLECTING",allocations:[]};
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:true,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return session;
   if(path==="/pos/payment-sessions/session-card/allocations")return {...session,status:"COVERED",allocations:[{id:(options?.body as {allocationId:string}).allocationId,status:"APPROVED",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS"}]};
   if(path==="/pos/payment-sessions/session-card/finalize")return {...session,status:"FINALIZED",ticketNumber:"T-CARD"};
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
  fireEvent.click(await screen.findByRole("button",{name:/Efectivo.*F10/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(allocationCalls).toBe(1));
  fireEvent.click(screen.getByRole("button",{name:/Efectivo.*F10/}));
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
  fireEvent.click(await screen.findByRole("button",{name:/Tarjeta.*F11/}));
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
  fireEvent.click(await screen.findByRole("button",{name:/Efectivo.*F10/}));
  fireEvent.click(screen.getByRole("button",{name:/20/}));
  fireEvent.click(screen.getByRole("button",{name:"Confirmar cobro"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:"Consultar estado"})).toBeEnabled());
  expect(screen.getByRole("dialog",{name:"Cobro en efectivo"})).toBeVisible();
  fireEvent.click(screen.getByRole("button",{name:"Consultar estado"}));
  await waitFor(()=>expect(screen.queryByRole("dialog",{name:"Cobro en efectivo"})).not.toBeInTheDocument());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:/Efectivo.*F10/}));
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
  fireEvent.click(await screen.findByRole("button",{name:/Tarjeta.*F11/}));
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
    return {...covered,status:"FINALIZED",ticketNumber:"T-RECOVERED"};
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
  await waitFor(()=>expect(props.onFinalized).toHaveBeenCalledWith("T-RECOVERED",1210,undefined));
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
 });

 it("clears card recovery state on safe cancellation so a later card checkout can start",async()=>{
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
   if(path==="/pos/payment-sessions/session-cancel-card-1/cancel")return {...first,status:"CANCELLED"};
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
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).toBeNull();
  fireEvent.click(screen.getByRole("button",{name:/Tarjeta/}));
  await waitFor(()=>expect(apiRequestMock.mock.calls.filter(([path])=>path==="/pos/payment-sessions/session-cancel-card-2/allocations")).toHaveLength(1));
 });

 it("clears card recovery state after compensation acknowledgement so a later card checkout can start",async()=>{
  const first={id:"session-ack-card-1",total:"12.10",status:"COLLECTING",allocations:[]};
  const second={id:"session-ack-card-2",total:"12.10",status:"COLLECTING",allocations:[]};
  let creations=0;
  apiRequestMock.mockImplementation(async(path:string,options?:{body?:unknown})=>{
   if(path==="/terminal-configuration/payment")return {rules:{cardManualEnabled:false,integratedCardEnabled:true},providerDescriptors:[],configuration:{provider:"GLOBAL_PAYMENTS",enabled:true}};
   if(path==="/pos/payment-sessions/active")return null;
   if(path==="/pos/payment-sessions")return ++creations===1?first:second;
   if(path==="/pos/payment-sessions/session-ack-card-1/allocations"){
    const id=(options?.body as {allocationId:string}).allocationId;
    return {...first,status:"COMPENSATION_REQUIRED",allocations:[{id,idempotencyKey:id,status:"TIMEOUT",kind:"INTEGRATED_CARD",amount:"12.10",provider:"GLOBAL_PAYMENTS",operationId:"op-ack"}]};
   }
   if(path==="/pos/payment-sessions/session-ack-card-1/compensation-ack")return {...first,status:"CANCELLED"};
   if(path==="/pos/payment-sessions/session-ack-card-2/allocations")return second;
   throw new Error(`unexpected request ${path}`);
  });
  render(createElement(SalePaymentCheckout,{locale:"es",totalCents:1210,sale:{customerId:null,lines:[{productId:"p-1",quantity:1,discount:0}]},permissions:["ADMIN"],terminal:{storeName:"Tienda",terminalCode:"01"},onFinalized:vi.fn()}));
  const card=await screen.findByRole("button",{name:/Tarjeta/});
  await waitFor(()=>expect(card).toBeEnabled());
  fireEvent.click(card);
  await waitFor(()=>expect(screen.getByRole("button",{name:"Registrar resolución administrativa"})).toBeEnabled());
  expect(localStorage.getItem("tpverp.payment-session.01.allocation-attempt")).not.toBeNull();
  fireEvent.click(screen.getByRole("button",{name:"Registrar resolución administrativa"}));
  const dialog=screen.getByRole("dialog",{name:"Resolución administrativa"});
  fireEvent.change(within(dialog).getByRole("textbox"),{target:{value:" Resuelta "}});
  fireEvent.click(within(dialog).getByRole("button",{name:"Confirmar"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:/Tarjeta/})).toBeEnabled());
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

  fireEvent.click(screen.getByRole("button",{name:/Tarjeta.*F11/}));
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
