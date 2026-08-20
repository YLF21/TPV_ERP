import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import { apiRequest, ApiError } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
 LegacyPaymentAllocationPanel,
 PaymentAllocationPanel,
 type CheckoutMethod,
 type MemberWalletSelection,
 type MemberWalletView,
 type VoucherLookup,
} from "./PaymentAllocationPanel";
import type { PaymentSession } from "../sale/paymentOrchestration";
import { PaymentOperationPanel, type PaymentOperationEvent, type PaymentOperationView } from "./PaymentOperationPanel";
import { CashPaymentDialog } from "./CashPaymentDialog";
import { IndividualPaymentActions } from "./IndividualPaymentActions";
import { ManualCardReferenceDialog } from "./ManualCardReferenceDialog";
import { getHardwareBridge } from "../hardware/hardware";
import { loadPaymentOperationHistory, loadPaymentRefundLines, printPaymentReceipt, queryPaymentOperation, refundPaymentOperation, voidPaymentOperation, type PaymentRefundLineOption, type PaymentRefundLineSelection } from "../sale/paymentOperations";
import {
 defaultCheckoutPaymentMethodConfiguration,
 loadPaymentMethods,
 resolveCheckoutPaymentMethodConfiguration,
} from "../sale/paymentMethods";
import {
 saleOperationAuthorizationComplete,
 saleOperationCredentials,
 type SaleOperationAuthorization,
 type SaleOperationCredentials,
} from "../sale/operationSecurity";
import {
 saleMutationCredentialsRequired,
 saleWithOperationAuthorizations,
 type SaleMutationAuthorizationRequirement,
 type SaleMutationOperationAuthorizations,
} from "../sale/saleMutationAuthorizations";
import type { ConfirmedTicketPrintSnapshot } from "../sale/ticketPrinting";
import type { IssuedVoucherPrintSnapshot } from "../sale/voucherPrinting";
import type { LocaleCode, Permission, TerminalContext } from "../types";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";
import { SaleMutationAuthorizationDialog } from "./SaleMutationAuthorizationDialog";

type Sale = {
  customerId: string | null;
  lines: Array<{ productId: string; quantity: number; discount: number; openUnitPrice?: number; temporaryName?: string; cartLineId?: string; temporaryPriceAuthorizationToken?: string }>;
  previousTicketImport?: {
    ticketId: string;
    fingerprint: string;
    serialNumbersBySourceLineId: Record<string, string[]>;
  };
  quoteFingerprint?: string;
  promotionalCouponCode?: string;
  checkoutDiscountAmount?: number;
  memberBalanceAmount?: number;
};
export type ServerSession = { id: string; total: number | string; documentTotal?: number | string; direction?: "SALE"|"REFUND"|"ZERO"; status: string; ticketId?: string; ticketNumber?: string; printTicket?: ConfirmedTicketPrintSnapshot; issuedVoucher?: IssuedVoucherPrintSnapshot; memberBalanceReservationId?: string; memberBalanceRequestedAmount?: number|string; memberBalanceAppliedAmount?: number|string; memberBalanceFailureCode?: string; allocations: Array<{ id: string; idempotencyKey: string; kind: "CASH"|"MANUAL_CARD"|"INTEGRATED_CARD"|"VOUCHER"|"TRANSFER"|"PENDING"|"MEMBER_CREDIT"; amount: number|string; delivered?: number|string; change?: number|string; comment?: string; provider?: string; operationId?: string; originalPaymentId?: string; status: string; voucherCode?: string; reference?: string; authorization?: string; message?: string }>; refundPaymentAvailability?: Array<{ paymentMethod:string; kind?:"CASH"|"MANUAL_CARD"|"INTEGRATED_CARD"|"VOUCHER"|"TRANSFER"|"PENDING"|"MEMBER_CREDIT"|null; originalAmount:number|string; refundedAmount:number|string; reservedAmount:number|string; availableAmount:number|string }>; voucherOnlyRefund?: boolean };
export type PaymentFinalizationSummary = (
 | { kind: "CASH"; totalCents: number; receivedCents: number }
 | { kind: "CARD" | "VOUCHER" | "MIXED" | "REFUND" | "ZERO"; totalCents: number; receivedCents?: never }
) & { issuedVoucher?: IssuedVoucherPrintSnapshot };
type Props = { locale: LocaleCode; currentUsername?: string; totalCents: number; sale: Sale; token?: string; permissions: Permission[]; terminal: TerminalContext; disabled?: boolean; showIndividualActions?: boolean; unifiedCheckout?: boolean; interfaceMode?: "KEYBOARD"|"TOUCH"; checkoutDiscountCents?: number; memberBalanceCents?: number; memberBalanceAvailableCents?: number; pricingReady?: boolean; preferredSessionId?: string; memberBalanceReservationId?: string; customerSelected?: boolean; voucherOnlyRefund?: boolean; testCashEnabled?: boolean; saleMutationAuthorizations?: readonly SaleMutationAuthorizationRequirement[] | null; manualCardPaymentAuthorization?: SaleOperationAuthorization | null; transferPaymentAuthorization?: SaleOperationAuthorization | null; refundPolicyOverrideAuthorization?: SaleOperationAuthorization | null; refundTenderOverrideAuthorization?: SaleOperationAuthorization | null; paymentTerminalVoidAuthorization?: SaleOperationAuthorization | null; paymentTerminalRefundAuthorization?: SaleOperationAuthorization | null; paymentCompensationAuthorization?: SaleOperationAuthorization | null; createPendingAuthorization?: SaleOperationAuthorization | null; creditOverrideAuthorization?: SaleOperationAuthorization | null; onCash?: () => void; onPending?: () => void; onDiscount?: (amountCents:number)=>void; onMemberBalance?: (amountCents:number)=>void; onHydrationChange?: (hydrated:boolean)=>void; onLockedChange?: (locked:boolean,reservedTotalCents?:number)=>void; onFinalized: (printTicket: ConfirmedTicketPrintSnapshot,summary:PaymentFinalizationSummary) => void };
type AuthorizationAction = { kind: "VOID" | "REFUND"; authorization: SaleOperationAuthorization; amount: string; options: PaymentRefundLineOption[]; lines: PaymentRefundLineSelection[] };
type PaymentAllocationInput = {kind:string;amountCents:number;provider?:string;voucherCode?:string;reference?:string;deliveredCents?:number;changeCents?:number;comment?:string};
type AllocationAuthorizationAction = {
 input:PaymentAllocationInput;
 requirements:readonly SaleMutationAuthorizationRequirement[];
 paymentOperationCode?:string;
 refundPolicyOperationCode?:string;
 refundTenderOperationCode?:string;
 operationAuthorization?:SaleOperationCredentials;
 refundPolicyAuthorization?:SaleOperationCredentials;
 finalizeWhenCovered:boolean;
 cashAttempt?:CashAttemptMetadata;
};
type CashAttemptMetadata = { sessionId?: string; receivedCents: number };
type PendingFinalizeAuthorization = {
 sessionId:string;
 cashAttempt?:CashAttemptMetadata;
 creditOverrideRequired:boolean;
};
type PendingFinalizeCredentials = {
 authorizerUsername?:string;
 authorizerPassword?:string;
 creditOverride?:{
  reason:string;
  authorizerUsername?:string;
  authorizerPassword?:string;
 };
};
const manualPaymentAuthorizationCopy = {
 es: {
  card: "Autorizar pago con tarjeta manual",
  transfer: "Autorizar pago por transferencia",
 },
 en: {
  card: "Authorize manual card payment",
  transfer: "Authorize transfer payment",
 },
 zh: {
  card: "授权手动刷卡付款",
  transfer: "授权转账付款",
 },
} as const;
class InvalidPaymentFinalizationSummaryError extends Error {}

const uuid=()=>globalThis.crypto?.randomUUID?.()??`${Date.now()}-${Math.random()}`;
type EntryCleanupOutcome={next?:ServerSession;error?:unknown};
const entryCleanupRegistry=new Map<string,Promise<EntryCleanupOutcome>>();
const entryCleanupDurableRejections=new Map<string,EntryCleanupOutcome>();
export const entryCleanupRegistrySizeForTest=()=>entryCleanupRegistry.size;
function sharedEntryCleanup(sessionId:string,request:()=>Promise<ServerSession>):Promise<EntryCleanupOutcome>{
 const durableRejection=entryCleanupDurableRejections.get(sessionId);
 if(durableRejection)return Promise.resolve(durableRejection);
 const existing=entryCleanupRegistry.get(sessionId);if(existing)return existing;
 const created:Promise<EntryCleanupOutcome>=request()
  .then(next=>({next} as EntryCleanupOutcome),error=>({error} as EntryCleanupOutcome))
  .then(outcome=>{
   const durableLiveModeRejection=outcome.error instanceof ApiError&&outcome.error.status===409;
   if(durableLiveModeRejection)entryCleanupDurableRejections.set(sessionId,outcome);
   if(entryCleanupRegistry.get(sessionId)===created){
    entryCleanupRegistry.delete(sessionId);
   }
   return outcome;
  });
 entryCleanupRegistry.set(sessionId,created);return created;
}
function serverSessionDirection(session: ServerSession): "SALE" | "REFUND" | "ZERO" {
 const documentTotal = Number(session.documentTotal ?? session.total);
 return session.direction ?? (documentTotal < 0 ? "REFUND" : documentTotal === 0 ? "ZERO" : "SALE");
}
const map=(s:ServerSession):PaymentSession=>({id:s.id,totalCents:Math.round(Number(s.total)*100),direction:serverSessionDirection(s),status:s.status==="COVERED"||s.status==="FINALIZED"?"COVERED":s.status==="COMPENSATION_REQUIRED"?"COMPENSATION_REQUIRED":"COLLECTING",allocations:s.allocations.map(a=>({...a,amountCents:Math.round(Number(a.amount)*100),deliveredCents:a.delivered==null?undefined:Math.round(Number(a.delivered)*100),changeCents:a.change==null?undefined:Math.round(Number(a.change)*100),status:a.status as never})),refundPaymentAvailability:(s.refundPaymentAvailability??[]).map(value=>({paymentMethod:value.paymentMethod,kind:value.kind,originalAmountCents:Math.round(Number(value.originalAmount)*100),refundedAmountCents:Math.round(Number(value.refundedAmount)*100),reservedAmountCents:Math.round(Number(value.reservedAmount)*100),availableAmountCents:Math.round(Number(value.availableAmount)*100)}))});
export const compensationGuidanceKey="payment.split.compensationGuidance";
export type PaymentLogoutPreparation="READY"|"BLOCKED";
export type SalePaymentCheckoutHandle={
 prepareLogout():Promise<PaymentLogoutPreparation>;
 prepareApplicationClose():Promise<PaymentLogoutPreparation>;
 triggerCash():void;
 triggerCard():void;
 triggerPending():void;
 openCheckout(method?:CheckoutMethod):void;
};
type CancellationResult="CANCELLED"|"NOT_CANCELLED"|"ERROR";
export async function prepareAutomaticExit(cancel:()=>Promise<CancellationResult>,_discard:()=>Promise<boolean>){return await cancel()==="CANCELLED"?"READY" as const:"BLOCKED" as const;}
export function paymentLogoutDisposition(session:ServerSession|null|undefined,hydrationComplete:boolean){
 if(!hydrationComplete)return "BLOCKED" as const;
 if(!session||session.status==="FINALIZED"||session.status==="CANCELLED")return "READY" as const;
 if(session.status!=="COLLECTING")return "BLOCKED" as const;
 return session.allocations.every(({status})=>["DECLINED","ERROR","CANCELLED"].includes(status))?"AUTO_CANCEL" as const:"BLOCKED" as const;
}
export function checkoutPresentation(status?:string|null,allocationStatuses:readonly string[]=[],safeRetry=false){
 if(!status||status==="FINALIZED"||status==="CANCELLED")return "INDIVIDUAL_ACTIONS";
 if(status==="COVERED")return "FINALIZE_RETRY";
 if(status==="COMPENSATION_REQUIRED")return "COMPENSATION";
 if(status==="COLLECTING"&&allocationStatuses.some(value=>value==="APPROVED"))return "SPLIT";
 if(status==="COLLECTING"&&(safeRetry||(allocationStatuses.length>0&&allocationStatuses.every(value=>value==="DECLINED"||value==="ERROR"||value==="CANCELLED"))))return "INDIVIDUAL_ACTIONS";
 return "RECOVERY";
}
export function paymentSessionLocksSale(status:string|null|undefined){return !!status&&status!=="FINALIZED"&&status!=="CANCELLED";}
export function canManuallyFinalizePayment(status:string|null|undefined,busy:boolean){return status==="COVERED"&&!busy;}
export function shouldFinalizeAfterAllocation(status:string,unifiedCheckout:boolean,kind:string,finalizeWhenCovered:boolean){
 return status==="COVERED"&&(!unifiedCheckout||kind==="INTEGRATED_CARD"||finalizeWhenCovered);
}
export function paymentSessionAfterFinalization<T>(ticketNumber:string|undefined,session:T){return ticketNumber?null:session;}
export function paymentFinalizationSummary(session:ServerSession,cashAttempt?:CashAttemptMetadata):PaymentFinalizationSummary{
 const totalCents=Math.round(Number(session.total)*100);
 const direction=serverSessionDirection(session);
 if(direction==="ZERO")return {kind:"ZERO",totalCents:0};
 if(direction==="REFUND")return {kind:"REFUND",totalCents:-totalCents};
 const effective=session.allocations.filter(allocation=>allocation.status==="APPROVED");
 const hasCash=effective.some(allocation=>allocation.kind==="CASH");
 const hasCard=effective.some(allocation=>allocation.kind==="MANUAL_CARD"||allocation.kind==="INTEGRATED_CARD");
 const hasVoucher=effective.some(allocation=>allocation.kind==="VOUCHER");
 const hasOther=effective.some(allocation=>allocation.kind==="TRANSFER"||allocation.kind==="PENDING"||allocation.kind==="MEMBER_CREDIT");
 if([hasCash,hasCard,hasVoucher,hasOther].filter(Boolean).length>1)return {kind:"MIXED",totalCents};
 if(hasCard)return {kind:"CARD",totalCents};
 if(hasVoucher)return {kind:"VOUCHER",totalCents};
 if(hasOther)return {kind:"MIXED",totalCents};
 if(!hasCash)throw new InvalidPaymentFinalizationSummaryError("covered_payment_has_no_effective_allocations");
 const authorizedCashCents=effective.filter(allocation=>allocation.kind==="CASH").reduce((sum,allocation)=>sum+Math.round(Number(allocation.delivered??allocation.amount)*100),0);
 const receivedCents=cashAttempt?.sessionId===session.id?cashAttempt.receivedCents:Math.min(totalCents,authorizedCashCents);
 return {kind:"CASH",totalCents,receivedCents};
}
export function stableAllocationAttempt<T>(stored:{sessionId:string;allocationId:string;input:T}|null,sessionId:string,input:T,generate:()=>string){return stored?.sessionId===sessionId&&JSON.stringify(stored.input)===JSON.stringify(input)?stored:{sessionId,allocationId:generate(),input};}
export function allocationRecoveryInput(input:PaymentAllocationInput){
 return {kind:input.kind,amountCents:input.amountCents,provider:input.provider};
}
export function allocationFailureIsSafePreEffect(error:ApiError){return [400,401,403,404,409,422].includes(error.status);}
export type AllocationFailureRecovery="RETRY_NEW_ATTEMPT"|"RETRY_SAME_ATTEMPT"|"UNCERTAIN";
export function allocationFailureRecovery(
 kind:PaymentAllocationInput["kind"],
 allocationAccepted:boolean,
 error:unknown,
):AllocationFailureRecovery{
 if(allocationAccepted)return "UNCERTAIN";
 if(error instanceof ApiError&&allocationFailureIsSafePreEffect(error))return "RETRY_NEW_ATTEMPT";
 return kind==="INTEGRATED_CARD"?"UNCERTAIN":"RETRY_SAME_ATTEMPT";
}
export function parseStoredAllocationAttempt<T>(value:string|null){
 if(!value)return null;
 try{
  const parsed=JSON.parse(value) as {sessionId?:unknown;allocationId?:unknown;input?:unknown};
  return typeof parsed.sessionId==="string"
   &&typeof parsed.allocationId==="string"
   &&parsed.input!==null
   &&typeof parsed.input==="object"
   ? parsed as {sessionId:string;allocationId:string;input:T}
   : null;
 }catch{return null;}
}
export function isMissingCashSessionError(message:string){const normalized=message.normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLocaleLowerCase("es");return normalized.includes("sesi")&&normalized.includes("de caja abierta");}
export function isCreditLimitExceededError(error:unknown){
 if(!(error instanceof ApiError)||error.status!==409)return false;
 return error.problem?.code==="CUSTOMER_CREDIT_LIMIT_EXCEEDED";
}
export function shouldOfferTestCashSession(enabled:boolean,status:string|undefined,missing:boolean,terminalId?:string){return enabled&&status==="COVERED"&&missing&&Boolean(terminalId);}
export async function authorizationPasswordIsEphemeral<T>(password:string,clear:(value:string)=>void,operation:(password:string)=>Promise<T>){clear("");try{return await operation(password);}finally{clear("");}}
export async function compensationNoteIsEphemeral<T>(note:string,clear:(value:string)=>void,operation:(note:string)=>Promise<T>){const normalized=note.trim();clear("");try{return await operation(normalized);}finally{clear("");}}

export const SalePaymentCheckout=forwardRef<SalePaymentCheckoutHandle,Props>(function SalePaymentCheckout({locale,currentUsername="",totalCents,sale,token,permissions,terminal,disabled,showIndividualActions=true,unifiedCheckout=false,interfaceMode="KEYBOARD",checkoutDiscountCents=0,memberBalanceCents=0,memberBalanceAvailableCents=0,pricingReady=true,preferredSessionId,memberBalanceReservationId,customerSelected=false,voucherOnlyRefund=false,testCashEnabled=false,saleMutationAuthorizations=[],manualCardPaymentAuthorization,transferPaymentAuthorization,refundPolicyOverrideAuthorization,refundTenderOverrideAuthorization,paymentTerminalVoidAuthorization,paymentTerminalRefundAuthorization,paymentCompensationAuthorization,createPendingAuthorization,creditOverrideAuthorization,onCash,onPending,onDiscount,onMemberBalance,onHydrationChange,onLockedChange,onFinalized},ref){
 const t=createTranslator(locale);
 const legacyPasswordAuthorization:SaleOperationAuthorization={mode:"CURRENT_PASSWORD",requireUsername:false,requirePassword:true};
 const legacyDirectAuthorization:SaleOperationAuthorization={mode:"DIRECT",requireUsername:false,requirePassword:false};
 const effectiveManualCardPaymentAuthorization=manualCardPaymentAuthorization===undefined
  ? legacyDirectAuthorization
  : manualCardPaymentAuthorization;
 const effectiveTransferPaymentAuthorization=transferPaymentAuthorization===undefined
  ? legacyDirectAuthorization
  : transferPaymentAuthorization;
 const effectiveRefundPolicyOverrideAuthorization=refundPolicyOverrideAuthorization===undefined
  ? legacyPasswordAuthorization
  : refundPolicyOverrideAuthorization;
 const effectiveRefundTenderOverrideAuthorization=refundTenderOverrideAuthorization===undefined
  ? legacyPasswordAuthorization
  : refundTenderOverrideAuthorization;
 const effectiveVoidAuthorization=paymentTerminalVoidAuthorization===undefined
  ? (permissions.includes("ADMIN")||permissions.includes("PAYMENT_TERMINAL_VOID")?legacyPasswordAuthorization:null)
  : paymentTerminalVoidAuthorization;
 const effectiveRefundAuthorization=paymentTerminalRefundAuthorization===undefined
  ? (permissions.includes("ADMIN")||permissions.includes("PAYMENT_TERMINAL_REFUND")?legacyPasswordAuthorization:null)
  : paymentTerminalRefundAuthorization;
 const effectiveCompensationAuthorization=paymentCompensationAuthorization===undefined
  ? (permissions.includes("ADMIN")||permissions.includes("PAYMENT_TERMINAL_REFUND")?legacyDirectAuthorization:null)
  : paymentCompensationAuthorization;
 const effectiveCreatePendingAuthorization=createPendingAuthorization===undefined
  ? (permissions.includes("ADMIN")||permissions.includes("CUSTOMER_RECEIVABLES_CREATE")?legacyDirectAuthorization:null)
  : createPendingAuthorization;
 const effectiveCreditOverrideAuthorization=creditOverrideAuthorization===undefined
  ? (permissions.includes("ADMIN")||permissions.includes("CUSTOMER_CREDIT_OVERRIDE")?legacyDirectAuthorization:null)
  : creditOverrideAuthorization;
 const pendingEnabled=Boolean(effectiveCreatePendingAuthorization);
 const [server,setServer]=useState<ServerSession|null>(null);const [providers,setProviders]=useState<string[]>([]);const [capabilities,setCapabilities]=useState<string[]>([]);const [manual,setManual]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState("");const [operation,setOperation]=useState<PaymentOperationView|null>(null);const [events,setEvents]=useState<PaymentOperationEvent[]>([]);
 const [authorization,setAuthorization]=useState<AuthorizationAction|null>(null);const [authorizationUsername,setAuthorizationUsername]=useState("");const [authorizationPassword,setAuthorizationPassword]=useState("");
 const [compensationDialog,setCompensationDialog]=useState(false);const [compensationNote,setCompensationNote]=useState("");const [compensationUsername,setCompensationUsername]=useState("");const [compensationPassword,setCompensationPassword]=useState("");
 const [pendingFinalizeAuthorization,setPendingFinalizeAuthorization]=useState<PendingFinalizeAuthorization|null>(null);const [pendingUsername,setPendingUsername]=useState("");const [pendingPassword,setPendingPassword]=useState("");const [creditOverrideReason,setCreditOverrideReason]=useState("");const [creditOverrideUsername,setCreditOverrideUsername]=useState("");const [creditOverridePassword,setCreditOverridePassword]=useState("");
 const [allocationAuthorizationAction,setAllocationAuthorizationAction]=useState<AllocationAuthorizationAction|null>(null);
 const [reservationAuthorizations,setReservationAuthorizations]=useState<readonly SaleMutationAuthorizationRequirement[]|null>(null);
 const ensureFlightRef=useRef<Promise<ServerSession>|null>(null);
 const serverRef=useRef<ServerSession|null>(null);
 const [cashOpen,setCashOpen]=useState(false);const cashGuardRef=useRef(false);const cashAttemptRef=useRef<CashAttemptMetadata|null>(null);
 const [manualCardOpen,setManualCardOpen]=useState(false);const cardGuardRef=useRef(false);
 const [checkoutOpen,setCheckoutOpen]=useState(false);const [initialMethod,setInitialMethod]=useState<CheckoutMethod>("CASH");
 const [paymentMethods,setPaymentMethods]=useState(defaultCheckoutPaymentMethodConfiguration);
 const [returnPolicy,setReturnPolicy]=useState<"REFUND_ALLOWED"|"EXCHANGE_OR_VOUCHER_ONLY">("REFUND_ALLOWED");
 const [vouchers,setVouchers]=useState<Array<{code:string;balance:number|string}>>([]);
 const [memberWallet,setMemberWallet]=useState<MemberWalletView|null>(null);
 const [pendingMemberWallet,setPendingMemberWallet]=useState<{
  requestedCents:number;
  desiredLoyaltyCents:number;
  returnCreditAvailableCents:number;
 }|null>(null);
 const memberWalletApplicationRef=useRef(false);
 const [voucherOpen,setVoucherOpen]=useState(false);const [voucherCode,setVoucherCode]=useState("");const [voucherAmount,setVoucherAmount]=useState("");
 const [safeRetry,setSafeRetry]=useState(false);
 const [testCashRequired,setTestCashRequired]=useState(false);
 const [testCashStatus,setTestCashStatus]=useState("");
 const [hydrationComplete,setHydrationComplete]=useState(false);
 const [hydrationFailed,setHydrationFailed]=useState(false);
 const [hydrationRetry,setHydrationRetry]=useState(0);
 const entryHydratedSessionIdRef=useRef<string|null>(null);
 const exitFeedbackRef=useRef<"payment.pending.logoutError"|"payment.pending.shutdownBlocked"|null>(null);
 const cleanupFlightRef=useRef<{sessionId:string;promise:Promise<boolean>}|null>(null);
 const simulatorDiscardAttemptedRef=useRef(new Set<string>());
 const storageKey=`tpverp.payment-session.${terminal.terminalId??terminal.terminalCode}`;const attemptKey=`${storageKey}.allocation-attempt`;
 useEffect(()=>{void apiRequest<{rules:{cardManualEnabled:boolean;integratedCardEnabled:boolean};providerDescriptors:Array<{provider:string;capabilities:string[]}>;configuration:{provider:string;enabled:boolean}}>("/terminal-configuration/payment",{token}).then(c=>{setManual(c.rules.cardManualEnabled);setProviders(c.rules.integratedCardEnabled&&c.configuration.enabled?[c.configuration.provider]:[]);setCapabilities(c.providerDescriptors.find(p=>p.provider===c.configuration.provider)?.capabilities??[]);}).catch(()=>{});},[token]);
 useEffect(()=>{void loadPaymentMethods(token).then(methods=>setPaymentMethods(resolveCheckoutPaymentMethodConfiguration(methods))).catch(()=>setPaymentMethods(defaultCheckoutPaymentMethodConfiguration));},[token]);
 useEffect(()=>{void apiRequest<{policy:"REFUND_ALLOWED"|"EXCHANGE_OR_VOUCHER_ONLY"}>("/return-policy",{token}).then(value=>setReturnPolicy(value.policy)).catch(()=>setReturnPolicy("REFUND_ALLOWED"));},[token]);
  useEffect(()=>{void apiRequest<Array<{code:string;balance:number|string;status:string}>>("/vouchers",{token}).then(values=>setVouchers(values.filter(value=>value.status==="ACTIVE"))).catch(()=>setVouchers([]));},[token]);
  useEffect(()=>{
   let current=true;
   if(!sale.customerId||!memberBalanceReservationId){setMemberWallet(null);return()=>{current=false;};}
   setMemberWallet(null);
   void apiRequest<MemberWalletView>(`/customers/${sale.customerId}/member-wallet`,{token})
    .then(wallet=>{if(current)setMemberWallet({...wallet,lots:wallet.lots.map(lot=>({...lot,expiresAt:lot.expiresAt??undefined}))});})
    .catch(()=>{if(current)setMemberWallet(null);});
   return()=>{current=false;};
  },[memberBalanceReservationId,sale.customerId,token]);
  useEffect(()=>{
   if(!pendingMemberWallet||!pricingReady||serverRef.current||memberWalletApplicationRef.current)return;
   const requestedSaleLoyaltyCents=Math.max(0,Math.round(Number(sale.memberBalanceAmount??0)*100));
   if(requestedSaleLoyaltyCents!==pendingMemberWallet.desiredLoyaltyCents)return;
   const returnCreditCents=Math.min(
    Math.max(0,pendingMemberWallet.requestedCents-memberBalanceCents),
    pendingMemberWallet.returnCreditAvailableCents,
    Math.max(0,totalCents),
   );
   memberWalletApplicationRef.current=true;
   setPendingMemberWallet(null);
   if(returnCreditCents>0){
    requestAllocation({kind:"MEMBER_CREDIT",amountCents:returnCreditCents});
   }
  },[memberBalanceCents,pendingMemberWallet,pricingReady,sale.memberBalanceAmount,totalCents]);
  async function resolveVoucher(code:string):Promise<VoucherLookup|null>{
   try{
    return await apiRequest<VoucherLookup>(`/vouchers/${encodeURIComponent(code.trim())}`,{token});
   }catch(failure){
    if(failure instanceof ApiError&&failure.status===404)return null;
    throw failure;
   }
  }
 useEffect(()=>{let current=true;entryHydratedSessionIdRef.current=null;setHydrationComplete(false);setHydrationFailed(false);void (async()=>{try{const active=await apiRequest<ServerSession|null>("/pos/payment-sessions/active",{token});if(current){if(active){entryHydratedSessionIdRef.current=active.id;setServer(active);globalThis.sessionStorage?.setItem(storageKey,active.id);}else{globalThis.sessionStorage?.removeItem(storageKey);globalThis.localStorage?.removeItem(attemptKey);}setHydrationComplete(true);}}catch{const id=globalThis.sessionStorage?.getItem(storageKey);if(id)try{const recovered=await apiRequest<ServerSession>(`/pos/payment-sessions/${id}`,{token});if(current){entryHydratedSessionIdRef.current=recovered.id;setServer(recovered);setHydrationComplete(true);}}catch{/* Recovery remains authoritative only after a successful response. */}if(current)setHydrationFailed(true);}})();return()=>{current=false;};},[storageKey,attemptKey,token,hydrationRetry]);
 useEffect(()=>onHydrationChange?.(hydrationComplete),[hydrationComplete,onHydrationChange]);
 useEffect(()=>{serverRef.current=server;},[server]);
 useEffect(()=>onLockedChange?.(paymentSessionLocksSale(server?.status),server?Math.round(Number(server.total)*100):undefined),[server,onLockedChange]);
 useEffect(()=>{if(unifiedCheckout&&server&&server.status!=="FINALIZED"&&server.status!=="CANCELLED")setCheckoutOpen(true);},[server?.id,server?.status,unifiedCheckout]);
 function clearRecoveryStorage(expectedSessionId?:string){const storedSessionId=globalThis.sessionStorage?.getItem(storageKey);const ownsStoredSession=!expectedSessionId||storedSessionId===expectedSessionId;if(ownsStoredSession)globalThis.sessionStorage?.removeItem(storageKey);const storedAttempt=globalThis.localStorage?.getItem(attemptKey);let attemptSessionId:string|undefined;try{attemptSessionId=storedAttempt?(JSON.parse(storedAttempt) as {sessionId?:string}).sessionId:undefined;}catch{/* Legacy malformed attempts belong to the matching stored session only. */}if(!expectedSessionId||attemptSessionId===expectedSessionId||(ownsStoredSession&&!attemptSessionId))globalThis.localStorage?.removeItem(attemptKey);}
 function clearRecoveredSession(expectedSessionId?:string){clearRecoveryStorage(expectedSessionId);if(expectedSessionId)simulatorDiscardAttemptedRef.current.delete(expectedSessionId);cashAttemptRef.current=null;cashGuardRef.current=false;cardGuardRef.current=false;exitFeedbackRef.current=null;setCashOpen(false);setManualCardOpen(false);setVoucherOpen(false);setVoucherCode("");setVoucherAmount("");setCompensationDialog(false);setCompensationNote("");setCompensationUsername("");setCompensationPassword("");setPendingFinalizeAuthorization(null);setPendingMemberWallet(null);setPendingUsername("");setPendingPassword("");setCreditOverrideReason("");setCreditOverrideUsername("");setCreditOverridePassword("");setAllocationAuthorizationAction(null);setReservationAuthorizations(null);setAuthorization(null);setAuthorizationUsername("");setAuthorizationPassword("");setOperation(null);setEvents([]);setSafeRetry(false);setTestCashRequired(false);setTestCashStatus("");setError("");setServer(null);}
 async function ensure(operationAuthorizations:SaleMutationOperationAuthorizations={}){
  if(serverRef.current)return serverRef.current;
  if(ensureFlightRef.current)return ensureFlightRef.current;
  const flight=(async()=>{
   const reservedSale=saleWithOperationAuthorizations(sale,operationAuthorizations);
   const storedId=globalThis.sessionStorage?.getItem(storageKey);
   const id=storedId??preferredSessionId??uuid();
   const reservationId=id===preferredSessionId?memberBalanceReservationId:undefined;
   globalThis.sessionStorage?.setItem(storageKey,id);
   let created:ServerSession;
   try{
    created=await apiRequest<ServerSession>("/pos/payment-sessions",{
     token,
     body:{sessionId:id,sale:reservedSale,...(reservationId?{memberBalanceReservationId:reservationId}:{})},
    });
   }catch(failure){
    if(!(failure instanceof ApiError)||failure.status!==409)throw failure;
    const active=await apiRequest<ServerSession|null>("/pos/payment-sessions/active",{token});
    if(!active)throw failure;
    created=await apiRequest<ServerSession>("/pos/payment-sessions",{
     token,
     body:{sessionId:active.id,sale:reservedSale,...(active.id===preferredSessionId&&memberBalanceReservationId?{memberBalanceReservationId}:{})},
    });
   }
   if(created.memberBalanceFailureCode&&memberBalanceCents>0){
    const applied=Math.max(0,Math.round(Number(created.memberBalanceAppliedAmount??0)*100));
    onMemberBalance?.(applied);
    setError(t("payment.memberBalance.unavailable"));
   }
   globalThis.sessionStorage?.setItem(storageKey,created.id);
   serverRef.current=created;
   setServer(created);
   return created;
  })();
  ensureFlightRef.current=flight;
  try{return await flight;}finally{if(ensureFlightRef.current===flight)ensureFlightRef.current=null;}
 }
 async function finish(
  next:ServerSession,
  cashAttempt?:CashAttemptMetadata,
  pendingCredentials?:PendingFinalizeCredentials,
 ){
  if(next.status!=="COVERED")return;
  const hasPending=next.allocations.some(
   allocation=>allocation.kind==="PENDING"&&allocation.status==="APPROVED",
  );
  if(hasPending&&!effectiveCreatePendingAuthorization){
   setError(t("pendingSale.authorization.configurationUnavailable"));
   return;
  }
  if(hasPending&&!pendingCredentials&&effectiveCreatePendingAuthorization?.mode!=="DIRECT"){
   setServer(next);
   setPendingUsername("");
   setPendingPassword("");
   setCreditOverrideReason("");
   setCreditOverrideUsername("");
   setCreditOverridePassword("");
   setPendingFinalizeAuthorization({
    sessionId:next.id,
    cashAttempt,
    creditOverrideRequired:false,
   });
   return;
  }
  const summary=paymentFinalizationSummary(next,cashAttempt);
  const body=hasPending
   ? pendingCredentials??saleOperationCredentials(
      effectiveCreatePendingAuthorization!,
      "",
      "",
     )
   : undefined;
  let done:ServerSession;
  try{
   done=await apiRequest<ServerSession>(
    `/pos/payment-sessions/${next.id}/finalize`,
    {token,method:"POST",...(body?{body}:{})},
   );
  }catch(failure){
   if(hasPending&&isCreditLimitExceededError(failure)&&effectiveCreditOverrideAuthorization){
    setServer(next);
    setPendingUsername("");
    setPendingPassword("");
    setCreditOverrideReason("");
    setCreditOverrideUsername("");
    setCreditOverridePassword("");
    setPendingFinalizeAuthorization({
     sessionId:next.id,
     cashAttempt,
     creditOverrideRequired:true,
    });
    setError("");
    return;
   }
   throw failure;
  }
  if(!done.ticketNumber||!done.printTicket)throw new InvalidPaymentFinalizationSummaryError("finalized_payment_has_no_print_snapshot");
  setPendingFinalizeAuthorization(null);
  setPendingUsername("");
  setPendingPassword("");
  setCreditOverrideReason("");
  setCreditOverrideUsername("");
  setCreditOverridePassword("");
  setServer(null);
  globalThis.sessionStorage?.removeItem(storageKey);
  globalThis.localStorage?.removeItem(attemptKey);
  setCashOpen(false);
  setCheckoutOpen(false);
  setManualCardOpen(false);
  setVoucherOpen(false);
  setVoucherCode("");
  setVoucherAmount("");
  setTestCashRequired(false);
  setTestCashStatus("");
  setError("");
  if(cashAttemptRef.current?.sessionId===done.id)cashAttemptRef.current=null;
  cashGuardRef.current=false;
  cardGuardRef.current=false;
  onFinalized(
   done.printTicket,
   done.issuedVoucher ? {...summary,issuedVoucher:done.issuedVoucher} : summary,
  );
 }
 function markTestCashRequirement(failure:unknown){const required=testCashEnabled&&failure instanceof ApiError&&isMissingCashSessionError(failure.message);setTestCashRequired(required);setTestCashStatus("");}
 async function openTestCashSession(){if(!testCashEnabled||!terminal.terminalId||busy)return;setBusy(true);setTestCashStatus("");try{await apiRequest("/cash/sessions/open",{token,body:{terminalId:terminal.terminalId}});setError("");setTestCashRequired(false);setTestCashStatus(t("payment.testCash.opened"));}catch(failure){setError(failure instanceof ApiError?failure.message:t("payment.testCash.error"));}finally{setBusy(false);}}
 async function retryFinish(){if(!server||server.status!=="COVERED")return;setBusy(true);setError("");const cashAttempt=cashAttemptRef.current?.sessionId===server.id?cashAttemptRef.current:undefined;try{await finish(server,cashAttempt);}catch(e){markTestCashRequirement(e);setError(e instanceof ApiError?e.message:t("payment.split.error.finalize"));}finally{setBusy(false);}}
 async function submitPendingFinalizeAuthorization(){
  const context=pendingFinalizeAuthorization;
  const pendingSession=server;
  if(!context||!pendingSession||pendingSession.id!==context.sessionId
   ||!effectiveCreatePendingAuthorization
   ||!saleOperationAuthorizationComplete(
    effectiveCreatePendingAuthorization,
    pendingUsername,
    pendingPassword,
   )
   ||(context.creditOverrideRequired&&(
    !effectiveCreditOverrideAuthorization
    ||!creditOverrideReason.trim()
    ||!saleOperationAuthorizationComplete(
     effectiveCreditOverrideAuthorization,
     creditOverrideUsername,
     creditOverridePassword,
    )
   )))return;
  const credentials:PendingFinalizeCredentials={
   ...saleOperationCredentials(
    effectiveCreatePendingAuthorization,
    pendingUsername,
    pendingPassword,
   ),
   ...(context.creditOverrideRequired&&effectiveCreditOverrideAuthorization
    ? {
       creditOverride:{
        reason:creditOverrideReason.trim(),
        ...saleOperationCredentials(
         effectiveCreditOverrideAuthorization,
         creditOverrideUsername,
         creditOverridePassword,
        ),
       },
      }
    : {}),
  };
  setPendingFinalizeAuthorization(null);
  setPendingUsername("");
  setPendingPassword("");
  setCreditOverrideReason("");
  setCreditOverrideUsername("");
  setCreditOverridePassword("");
  setBusy(true);
  setError("");
  try{
   await finish(pendingSession,context.cashAttempt,credentials);
  }catch(failure){
   setPendingFinalizeAuthorization(context);
   setError(failure instanceof ApiError?failure.message:t("payment.split.error.finalize"));
  }finally{
   setPendingUsername("");
   setPendingPassword("");
   setCreditOverrideUsername("");
   setCreditOverridePassword("");
   setBusy(false);
  }
 }
 async function add(
  input:PaymentAllocationInput,
  cashAttempt?:CashAttemptMetadata,
  finalizeWhenCovered=false,
  operationAuthorization?:SaleOperationCredentials,
  refundPolicyAuthorization?:SaleOperationCredentials,
  refundTenderAuthorization?:SaleOperationCredentials,
  saleOperationAuthorizations:SaleMutationOperationAuthorizations={},
 ){
  setBusy(true);
  setError("");
  setSafeRetry(false);
  let s:ServerSession|undefined;
  let allocationAccepted=false;
  try{
    s=await ensure(saleOperationAuthorizations);
   if(cashAttempt){
    cashAttempt.sessionId=s.id;
    cashAttemptRef.current=cashAttempt;
   }
   const recoveryInput=allocationRecoveryInput(input);
   const stored=globalThis.localStorage?.getItem(attemptKey)??null;
   const parsed=parseStoredAllocationAttempt<typeof recoveryInput>(stored);
   if(stored&&!parsed)globalThis.localStorage?.removeItem(attemptKey);
   const attempt=stableAllocationAttempt(parsed,s.id,recoveryInput,uuid);
   const allocationId=attempt.allocationId;
   globalThis.localStorage?.setItem(attemptKey,JSON.stringify(attempt));
   const next=await apiRequest<ServerSession>(`/pos/payment-sessions/${s.id}/allocations`,{
    token,
    body:{
     allocationId,
     idempotencyKey:allocationId,
     kind:input.kind,
     amount:(input.amountCents/100).toFixed(2),
     provider:input.provider,
     voucherCode:input.voucherCode,
     reference:input.reference,
     delivered:input.deliveredCents==null?undefined:(input.deliveredCents/100).toFixed(2),
     change:input.changeCents==null?undefined:(input.changeCents/100).toFixed(2),
     comment:input.comment,
     ...((input.kind==="MANUAL_CARD"||input.kind==="TRANSFER")
      ? {operationAuthorization:operationAuthorization??{}}
      : {}),
     ...(refundPolicyAuthorization
      ? {refundPolicyAuthorization}
      : {}),
     ...(refundTenderAuthorization
      ? {refundTenderAuthorization}
      : {}),
    },
   });
   allocationAccepted=true;
   setServer(next);
   const allocationStatus=next.allocations.find(a=>a.id===allocationId)?.status;
   const safeTerminal=allocationStatus==="DECLINED"||allocationStatus==="ERROR";
   if(safeTerminal){
    globalThis.localStorage?.removeItem(attemptKey);
    if(input.kind==="CASH"){
     cashAttemptRef.current=null;
     cashGuardRef.current=false;
     setCashOpen(false);
    }else{
     cardGuardRef.current=false;
     setManualCardOpen(false);
    }
   }else if(next.status!=="COVERED"&&!next.allocations.some(
    a=>a.id===allocationId&&(a.status==="PENDING"||a.status==="TIMEOUT"),
   )){
    globalThis.localStorage?.removeItem(attemptKey);
   }
   if(shouldFinalizeAfterAllocation(
    next.status,
    unifiedCheckout,
    input.kind,
    finalizeWhenCovered,
   )){
    await finish(next,cashAttempt);
   }
  }catch(e){
   if(e instanceof ApiError
    &&e.problem?.code==="REFUND_TENDER_OVERRIDE_REQUIRED"
    &&refundTenderAuthorization===undefined){
    globalThis.localStorage?.removeItem(attemptKey);
    if(effectiveRefundTenderOverrideAuthorization===null){
     if(cashAttemptRef.current===cashAttempt)cashAttemptRef.current=null;
     if(cashAttempt)cashGuardRef.current=false;
     if(input.kind==="MANUAL_CARD"||input.kind==="INTEGRATED_CARD")cardGuardRef.current=false;
     setError(t("sale.operationSecurity.unavailable"));
     return;
    }
    if(effectiveRefundTenderOverrideAuthorization.mode==="DIRECT"){
     setTimeout(()=>void add(
      input,cashAttempt,finalizeWhenCovered,operationAuthorization,
      refundPolicyAuthorization,{},saleOperationAuthorizations,
     ),0);
     return;
    }
    setError("");
    setAllocationAuthorizationAction({
     input,
     requirements:[{
      code:"REFUND_TENDER_OVERRIDE",
      label:t("gestion.salesOperationSecurity.operation.REFUND_TENDER_OVERRIDE"),
      authorization:effectiveRefundTenderOverrideAuthorization,
     }],
     refundTenderOperationCode:"REFUND_TENDER_OVERRIDE",
     operationAuthorization,
     refundPolicyAuthorization,
     finalizeWhenCovered,
     cashAttempt,
    });
    return;
   }
   markTestCashRequirement(e);
   const recovery=allocationFailureRecovery(input.kind,allocationAccepted,e);
   if(e instanceof InvalidPaymentFinalizationSummaryError){
    setError(t("payment.split.error.finalize"));
   }else if(recovery!=="UNCERTAIN"){
    if(recovery==="RETRY_NEW_ATTEMPT")globalThis.localStorage?.removeItem(attemptKey);
    if(cashAttemptRef.current===cashAttempt)cashAttemptRef.current=null;
    if(cashAttempt)cashGuardRef.current=false;
    if(input.kind==="MANUAL_CARD"||input.kind==="INTEGRATED_CARD")cardGuardRef.current=false;
    setSafeRetry(true);
    setError(e instanceof ApiError?e.message:t("payment.split.error.retryable"));
   }else{
    setError(e instanceof ApiError
     ?`${e.message}. ${t("payment.split.error.uncertainSame")}`
     :t("payment.split.error.uncertain"));
    if(s&&!(allocationAccepted&&e instanceof ApiError&&e.status===409&&isMissingCashSessionError(e.message))){
     void apiRequest<ServerSession>("/pos/payment-sessions/active",{token})
      .then(active=>setServer(active))
      .catch(()=>{});
    }
   }
  }finally{
   setBusy(false);
  }
 }
 function paymentAuthorizationFor(kind:string){
  if(kind==="MANUAL_CARD")return effectiveManualCardPaymentAuthorization;
  if(kind==="TRANSFER")return effectiveTransferPaymentAuthorization;
  return undefined;
 }
 function paymentOperationCodeFor(kind:string){
  if(kind==="MANUAL_CARD")return "CONFIRM_MANUAL_CARD_PAYMENT";
  if(kind==="TRANSFER")return "CONFIRM_TRANSFER_PAYMENT";
  return undefined;
 }
 function requestAllocation(
  input:PaymentAllocationInput,
  finalizeWhenCovered=false,
  cashAttempt?:CashAttemptMetadata,
 ){
 const authorization=paymentAuthorizationFor(input.kind);
  const refundCheckout=(server?.direction??(totalCents<0?"REFUND":"SALE"))==="REFUND";
  const effectiveVoucherOnlyRefund=server?.voucherOnlyRefund??voucherOnlyRefund;
  if(refundCheckout&&effectiveVoucherOnlyRefund&&input.kind!=="VOUCHER"&&input.kind!=="MEMBER_CREDIT"){
   if(input.kind==="MANUAL_CARD"||input.kind==="INTEGRATED_CARD")cardGuardRef.current=false;
   if(input.kind==="CASH")cashGuardRef.current=false;
   setError(locale==="es"
    ?"Los tickets regalo solo pueden devolverse mediante un vale o saldo a favor."
    :locale==="en"
     ?"Gift receipt returns can only be refunded as a voucher or return credit."
     :"礼品小票退货只能退还为代金券或退货余额。");
   return;
  }
  const refundPolicyRequired=refundCheckout
   &&returnPolicy==="EXCHANGE_OR_VOUCHER_ONLY"
   &&(input.kind==="CASH"||input.kind==="MANUAL_CARD"||input.kind==="INTEGRATED_CARD");
  const refundAuthorization=refundPolicyRequired
   ? effectiveRefundPolicyOverrideAuthorization
   : undefined;
  if(authorization===null){
    if(input.kind==="MANUAL_CARD")cardGuardRef.current=false;
    setError(t("sale.operationSecurity.unavailable"));
    return;
  }
  if(refundAuthorization===null){
   if(input.kind==="MANUAL_CARD"||input.kind==="INTEGRATED_CARD")cardGuardRef.current=false;
   if(input.kind==="CASH")cashGuardRef.current=false;
   setError(t("sale.operationSecurity.unavailable"));
   return;
  }
  if(!server&&saleMutationAuthorizations===null){
   if(input.kind==="MANUAL_CARD"||input.kind==="INTEGRATED_CARD")cardGuardRef.current=false;
   if(input.kind==="CASH")cashGuardRef.current=false;
   setError(t("sale.operationSecurity.unavailable"));
   return;
  }
  const paymentOperationCode=paymentOperationCodeFor(input.kind);
  const requirements=[
   ...(!server&&saleMutationAuthorizations
    ? saleMutationCredentialsRequired(saleMutationAuthorizations)
    : []),
   ...(authorization&&authorization.mode!=="DIRECT"&&paymentOperationCode
    ? [{
       code:paymentOperationCode,
       label:input.kind==="MANUAL_CARD"
        ? manualPaymentAuthorizationCopy[locale].card
        : manualPaymentAuthorizationCopy[locale].transfer,
       authorization,
      }]
    : []),
   ...(refundAuthorization&&refundAuthorization.mode!=="DIRECT"
    ? [{
       code:"REFUND_POLICY_OVERRIDE",
       label:t("gestion.salesOperationSecurity.operation.REFUND_POLICY_OVERRIDE"),
       authorization:refundAuthorization,
      }]
    : []),
  ];
  if(requirements.length===0){
   void add(
    input,
    cashAttempt,
    finalizeWhenCovered,
    authorization?{}:undefined,
    refundAuthorization?{}:undefined,
   );
   return;
  }
  setAllocationAuthorizationAction({
    input,
    requirements,
   paymentOperationCode,
   refundPolicyOperationCode:refundAuthorization?"REFUND_POLICY_OVERRIDE":undefined,
    finalizeWhenCovered,
    cashAttempt,
   });
 }
 function submitAllocationAuthorization(
  authorizations:SaleMutationOperationAuthorizations,
 ){
  const action=allocationAuthorizationAction;
  if(!action)return;
  setAllocationAuthorizationAction(null);
  const saleAuthorizations={...authorizations};
  const paymentAuthorization=action.paymentOperationCode
   ? saleAuthorizations[action.paymentOperationCode]
   : action.operationAuthorization;
  if(action.paymentOperationCode)delete saleAuthorizations[action.paymentOperationCode];
  const refundPolicyAuthorization=action.refundPolicyOperationCode
   ? saleAuthorizations[action.refundPolicyOperationCode]
   : action.refundPolicyAuthorization;
  if(action.refundPolicyOperationCode)delete saleAuthorizations[action.refundPolicyOperationCode];
  const refundTenderAuthorization=action.refundTenderOperationCode
   ? saleAuthorizations[action.refundTenderOperationCode]
   : undefined;
  if(action.refundTenderOperationCode)delete saleAuthorizations[action.refundTenderOperationCode];
  void add(
   action.input,
   action.cashAttempt,
   action.finalizeWhenCovered,
   paymentAuthorization,
   refundPolicyAuthorization,
   refundTenderAuthorization,
   saleAuthorizations,
  );
 }
 function cancelAllocationAuthorization(){
  if(allocationAuthorizationAction?.input.kind==="MANUAL_CARD"
   ||allocationAuthorizationAction?.input.kind==="INTEGRATED_CARD"){
    cardGuardRef.current=false;
   }
  if(allocationAuthorizationAction?.input.kind==="CASH"){
   cashGuardRef.current=false;
   cashAttemptRef.current=null;
  }
  setAllocationAuthorizationAction(null);
 }
 async function applyMemberWalletSelection(selection:MemberWalletSelection){
  if(!onMemberBalance||busy)return;
  const current=serverRef.current;
  const hasEffectiveAllocation=current?.allocations.some(
   allocation=>!["DECLINED","ERROR","CANCELLED"].includes(allocation.status),
  )??false;
  if(hasEffectiveAllocation){
   setError(locale==="es"
    ?"El saldo debe aplicarse antes de registrar otros pagos."
    :locale==="en"
     ?"Member balance must be applied before recording other payments."
     :"会员余额必须在登记其他付款前使用。");
   return;
  }
  if(current){
   const cancelled=await cancel();
   if(cancelled!=="CANCELLED")return;
  }
  setError("");
  memberWalletApplicationRef.current=false;
  setPendingMemberWallet({
   requestedCents:selection.requestedCents,
   desiredLoyaltyCents:selection.loyaltyCents,
   returnCreditAvailableCents:selection.returnCreditAvailableCents,
  });
  onMemberBalance(selection.loyaltyCents);
 }
 function confirmCash(receivedCents:number){if(cashGuardRef.current)return;cashGuardRef.current=true;const cashAttempt={receivedCents};cashAttemptRef.current=cashAttempt;requestAllocation({kind:"CASH",amountCents:totalCents},false,cashAttempt);}
 function startCard(){if(cardGuardRef.current)return;if(providers[0]){cardGuardRef.current=true;requestAllocation({kind:"INTEGRATED_CARD",amountCents:totalCents,provider:providers[0]});}else if(manual){setManualCardOpen(true);}}
 function confirmManualCard(reference:string){if(cardGuardRef.current)return;cardGuardRef.current=true;setManualCardOpen(false);requestAllocation({kind:"MANUAL_CARD",amountCents:totalCents,reference});}
 function openVoucher(){if(!paymentMethods.voucherActive||vouchers.length===0)return;const first=vouchers[0];setVoucherCode(first.code);setVoucherAmount((Math.min(totalCents,Math.round(Number(first.balance)*100))/100).toFixed(2));setVoucherOpen(true);}
 function confirmVoucher(){const voucher=vouchers.find(value=>value.code===voucherCode);const amountCents=Math.round(Number(voucherAmount.replace(",","."))*100);if(!voucher||amountCents<=0||amountCents>totalCents||amountCents>Math.round(Number(voucher.balance)*100))return;setVoucherOpen(false);requestAllocation({kind:"VOUCHER",amountCents,voucherCode});}
 async function query(allocationId:string){if(!server)return;setBusy(true);setError("");const queried=server.allocations.find(a=>a.id===allocationId||a.operationId===allocationId);const cashAttempt=queried?.kind==="CASH"&&cashAttemptRef.current?.sessionId===server.id?cashAttemptRef.current:undefined;try{const next=await apiRequest<ServerSession>(`/pos/payment-sessions/${server.id}/allocations/${allocationId}/query`,{token,method:"POST"});setServer(next);const resolved=next.allocations.find(a=>a.id===allocationId||a.operationId===allocationId);const safeTerminal=resolved?.status==="DECLINED"||resolved?.status==="ERROR";if(safeTerminal){globalThis.localStorage?.removeItem(attemptKey);if(resolved.kind==="CASH"){cashAttemptRef.current=null;cashGuardRef.current=false;setCashOpen(false);}else{cardGuardRef.current=false;setManualCardOpen(false);}}else if(next.status!=="COVERED"&&!(resolved?.status==="PENDING"||resolved?.status==="TIMEOUT"))globalThis.localStorage?.removeItem(attemptKey);await finish(next,cashAttempt);}catch(e){setError(e instanceof ApiError?e.message:t("payment.split.error.query"));}finally{setBusy(false);}}
 async function cancel():Promise<CancellationResult>{
  if(!server)return "NOT_CANCELLED";
  setBusy(true);setError("");
  try{
   const sessionId=server.id;
   const next=await apiRequest<ServerSession>(`/pos/payment-sessions/${sessionId}/cancel`,{token,method:"POST"});
   setServer(next);
   if(next.status==="CANCELLED"){clearRecoveredSession(sessionId);return "CANCELLED";}
   if(next.status==="COMPENSATION_REQUIRED"){
    if(simulatorDiscardAttemptedRef.current.has(sessionId)){
     setError(t("payment.split.error.cancelUnresolved"));
     return "NOT_CANCELLED";
    }
    simulatorDiscardAttemptedRef.current.add(sessionId);
    try{
     const discarded=await apiRequest<ServerSession>(`/pos/payment-sessions/${sessionId}/simulator-discard`,{token,body:{reason:"payment_method_change"}});
     setServer(discarded);
     if(discarded.status==="CANCELLED"){clearRecoveredSession(sessionId);return "CANCELLED";}
    }catch{
     simulatorDiscardAttemptedRef.current.delete(sessionId);
     setError(t("payment.split.error.cancelUnresolved"));
     return "NOT_CANCELLED";
    }
   }
   setError(t("payment.split.error.cancelCompensation"));
   return "NOT_CANCELLED";
  }catch(e){setError(e instanceof ApiError?e.message:t("payment.split.error.cancel"));return "ERROR";}
  finally{setBusy(false);}
 }
 async function discardSimulator(reason:"application_shutdown"|"sale_entry_cleanup",feedbackKey:"payment.pending.logoutError"|"payment.pending.shutdownBlocked"|"payment.pending.simulatorCleanupError"){if(!server)return false;const sessionId=server.id;if(simulatorDiscardAttemptedRef.current.has(sessionId)){setError(t(feedbackKey==="payment.pending.simulatorCleanupError"?(exitFeedbackRef.current??feedbackKey):feedbackKey));return false;}simulatorDiscardAttemptedRef.current.add(sessionId);setBusy(true);try{const next=await apiRequest<ServerSession>(`/pos/payment-sessions/${sessionId}/simulator-discard`,{token,body:{reason}});setServer(next);if(next.status==="CANCELLED"){clearRecoveredSession(next.id);return true;}setError(t(feedbackKey==="payment.pending.simulatorCleanupError"?(exitFeedbackRef.current??feedbackKey):feedbackKey));return false;}catch{simulatorDiscardAttemptedRef.current.delete(sessionId);setError(t(feedbackKey==="payment.pending.simulatorCleanupError"?(exitFeedbackRef.current??feedbackKey):feedbackKey));return false;}finally{setBusy(false);}}
 function cleanupSingleFlight(sessionId:string,operation:()=>Promise<boolean>){const current=cleanupFlightRef.current;if(current?.sessionId===sessionId)return current.promise;const promise=operation().finally(()=>{if(cleanupFlightRef.current?.promise===promise)cleanupFlightRef.current=null;});cleanupFlightRef.current={sessionId,promise};return promise;}
 async function prepareExit(feedbackKey:"payment.pending.logoutError"|"payment.pending.shutdownBlocked"){exitFeedbackRef.current=feedbackKey;const disposition=paymentLogoutDisposition(server,hydrationComplete);if(disposition==="READY"){clearRecoveredSession(server?.id);return "READY" as const;}if(!server){setError(t(feedbackKey));return "BLOCKED" as const;}const cleaned=await cleanupSingleFlight(server.id,disposition==="AUTO_CANCEL"?async()=>await cancel()==="CANCELLED":()=>discardSimulator("application_shutdown",feedbackKey));if(cleaned)return "READY" as const;setError(t(feedbackKey));return "BLOCKED" as const;}
 function individualActionsAvailable(){return checkoutPresentation(server?.status,server?.allocations.map(allocation=>allocation.status),safeRetry)==="INDIVIDUAL_ACTIONS"&&!disabled&&!busy&&totalCents>0;}
 async function reserveCheckout(authorizations:SaleMutationOperationAuthorizations={}){
  setBusy(true);
  setError("");
  try{
   const next=await ensure(authorizations);
   setServer(next);
  }catch(failure){
   setError(failure instanceof ApiError?failure.message:t("payment.split.error.reserve"));
  }finally{
   setBusy(false);
  }
 }
 function openCheckout(method:CheckoutMethod="CASH"){
  if(disabled||busy)return;
  setInitialMethod(method);
  setCheckoutOpen(true);
  if(totalCents>0||server)return;
  setError("");
  if(saleMutationAuthorizations===null){
   setError(t("sale.operationSecurity.unavailable"));
   return;
  }
  const requirements=saleMutationCredentialsRequired(saleMutationAuthorizations);
  if(requirements.length>0){
   setReservationAuthorizations(requirements);
   return;
  }
  void reserveCheckout();
 }
 function submitReservationAuthorizations(authorizations:SaleMutationOperationAuthorizations){
  setReservationAuthorizations(null);
  void reserveCheckout(authorizations);
 }
 function triggerCash(){if(!paymentMethods.cashActive||!individualActionsAvailable()||cashOpen||manualCardOpen)return;(onCash??(()=>setCashOpen(true)))();}
 function triggerCard(){if(!paymentMethods.cardActive||!individualActionsAvailable()||cashOpen||manualCardOpen||(!manual&&providers.length===0))return;startCard();}
 function triggerPending(){if(!pendingEnabled||!individualActionsAvailable()||cashOpen||manualCardOpen)return;onPending?.();}
 async function clearCheckout(closeAfter:boolean){
  if(!server){
   setReservationAuthorizations(null);
   if(checkoutDiscountCents>0)onDiscount?.(0);
   if(memberBalanceCents>0)onMemberBalance?.(0);
   if(closeAfter)setCheckoutOpen(false);
   return;
  }
  const result=await cancel();
  if(result==="CANCELLED"){
   setServer(null);
   cashAttemptRef.current=null;
   cashGuardRef.current=false;
   cardGuardRef.current=false;
   if(checkoutDiscountCents>0)onDiscount?.(0);
   if(memberBalanceCents>0)onMemberBalance?.(0);
   if(closeAfter)setCheckoutOpen(false);
  }
 }
 useImperativeHandle(ref,()=>({prepareLogout:()=>prepareExit("payment.pending.logoutError"),prepareApplicationClose:()=>prepareExit("payment.pending.shutdownBlocked"),triggerCash,triggerCard,triggerPending,openCheckout}));
 useEffect(()=>{if(!hydrationComplete||!server||entryHydratedSessionIdRef.current!==server.id||paymentLogoutDisposition(server,true)==="READY")return;let current=true;const sessionId=server.id;simulatorDiscardAttemptedRef.current.add(sessionId);setBusy(true);const cleanup=sharedEntryCleanup(sessionId,()=>apiRequest<ServerSession>(`/pos/payment-sessions/${sessionId}/simulator-discard`,{token,body:{reason:"sale_entry_cleanup"}}));const completion=cleanup.then(({next})=>next?.status==="CANCELLED");cleanupFlightRef.current={sessionId,promise:completion};void cleanup.then(({next,error})=>{if(!current||entryHydratedSessionIdRef.current!==sessionId)return;if(next){setServer(next);if(next.status==="CANCELLED")clearRecoveredSession(sessionId);else setError(t(exitFeedbackRef.current??"payment.pending.simulatorCleanupError"));}else if(error){simulatorDiscardAttemptedRef.current.delete(sessionId);setError(t(exitFeedbackRef.current??"payment.pending.simulatorCleanupError"));}}).finally(()=>{if(cleanupFlightRef.current?.promise===completion)cleanupFlightRef.current=null;if(current&&entryHydratedSessionIdRef.current===sessionId)setBusy(false);});return()=>{current=false;};},[hydrationComplete,server?.id,token]);
 async function acknowledge(){if(!server||!effectiveCompensationAuthorization||!compensationNote.trim()||!saleOperationAuthorizationComplete(effectiveCompensationAuthorization,compensationUsername,compensationPassword))return;const credentials=saleOperationCredentials(effectiveCompensationAuthorization,compensationUsername,compensationPassword);setCompensationDialog(false);setCompensationUsername("");setCompensationPassword("");setBusy(true);await compensationNoteIsEphemeral(compensationNote,setCompensationNote,async note=>{try{const next=await apiRequest<ServerSession>(`/pos/payment-sessions/${server.id}/compensation-ack`,{token,body:{note,...credentials}});setServer(next);if(next.status==="CANCELLED")clearRecoveredSession();}catch(e){setError(e instanceof ApiError?e.message:t("payment.split.error.acknowledge"));}finally{setCompensationUsername("");setCompensationPassword("");setBusy(false);}});}
 async function manage(id:string){setBusy(true);try{const [op,history]=await Promise.all([apiRequest<PaymentOperationView>(`/payment-terminal/operations/${id}`,{token}),loadPaymentOperationHistory(id,token)]);setOperation(op);setEvents(history);}catch(e){setError(e instanceof ApiError?e.message:t("payment.split.error.loadOperation"));}finally{setBusy(false);}}
 async function openRefundAuthorization(){if(!operation||busy||!effectiveRefundAuthorization)return;setBusy(true);setError("");try{const options=await loadPaymentRefundLines(operation.id,token);setAuthorizationUsername("");setAuthorizationPassword("");setAuthorization({kind:"REFUND",authorization:effectiveRefundAuthorization,amount:"",options,lines:[]});}catch(e){setError(e instanceof ApiError?e.message:t("payment.split.error.refund"));}finally{setBusy(false);}}
 async function authorize(){if(!authorization||!operation||!saleOperationAuthorizationComplete(authorization.authorization,authorizationUsername,authorizationPassword))return;const action=authorization;const username=authorizationUsername;setAuthorization(null);setAuthorizationUsername("");await authorizationPasswordIsEphemeral(authorizationPassword,setAuthorizationPassword,async password=>{const credentials=saleOperationCredentials(action.authorization,username,password);try{const next=action.kind==="VOID"?await voidPaymentOperation(operation.id,token,credentials,uuid()):await refundPaymentOperation(operation.id,token,action.amount,credentials,uuid(),action.lines);setOperation(next);}catch(e){setError(e instanceof ApiError?e.message:t(action.kind==="VOID"?"payment.split.error.void":"payment.split.error.refund"));}finally{setAuthorizationUsername("");}});}
 function calculatedRefundAmount(options:PaymentRefundLineOption[],lines:PaymentRefundLineSelection[]){const amount=lines.reduce((sum,line)=>{const option=options.find(value=>value.lineId===line.lineId);const available=Number(option?.refundableQuantity??0);const quantity=Number(line.quantity.replace(",","."));return sum+(option&&available>0&&Number.isFinite(quantity)?Number(option.refundableTotal)*quantity/available:0);},0);return amount>0?amount.toFixed(2):"";}
 function toggleRefundLine(option:PaymentRefundLineOption,checked:boolean){if(!authorization||authorization.kind!=="REFUND")return;const lines=checked?[...authorization.lines,{lineId:option.lineId,quantity:String(option.refundableQuantity),serialNumbers:option.refundableSerialNumbers??[]}]:authorization.lines.filter(line=>line.lineId!==option.lineId);setAuthorization({...authorization,lines,amount:calculatedRefundAmount(authorization.options,lines)});}
 function updateRefundLine(lineId:string,quantity:string){if(!authorization||authorization.kind!=="REFUND")return;const lines=authorization.lines.map(line=>line.lineId===lineId?{...line,quantity}:line);setAuthorization({...authorization,lines,amount:calculatedRefundAmount(authorization.options,lines)});}
 const cancelCashDialog=()=>{if(cashGuardRef.current)return;cashAttemptRef.current=null;setCashOpen(false);};
 const testCashOffered=shouldOfferTestCashSession(testCashEnabled,server?.status,testCashRequired,terminal.terminalId);
 const cashDialog=cashOpen?<CashPaymentDialog totalCents={totalCents} submitting={busy} error={error} initialMode="touch" onCancel={cancelCashDialog} onConfirm={server?.status==="COVERED"?()=>void retryFinish():confirmCash} testCashAction={testCashOffered?{label:busy?t("payment.testCash.opening"):t("payment.testCash.open"),onOpen:()=>void openTestCashSession()}:undefined} testCashStatus={testCashStatus?t("payment.testCash.openedDialog"):""}/>:null;
 const manualCardDialog=manualCardOpen?<div className="sale-action-overlay manual-card-payment-overlay"><div className="manual-card-payment-dialog"><ManualCardReferenceDialog busy={busy} onCancel={()=>setManualCardOpen(false)} onConfirm={confirmManualCard}/></div></div>:null;
 const manualPaymentAuthorizationDialog=<SaleMutationAuthorizationDialog
  open={Boolean(allocationAuthorizationAction)}
  locale={locale}
  currentUsername={currentUsername}
  requirements={allocationAuthorizationAction?.requirements??[]}
  busy={busy}
  error={error}
  onCancel={cancelAllocationAuthorization}
  onConfirm={submitAllocationAuthorization}
 />;
 const reservationAuthorizationDialog=<SaleMutationAuthorizationDialog
  open={Boolean(reservationAuthorizations)}
  locale={locale}
  currentUsername={currentUsername}
  requirements={reservationAuthorizations??[]}
  busy={busy}
  error={error}
  onCancel={()=>{setReservationAuthorizations(null);setError("");}}
  onConfirm={submitReservationAuthorizations}
 />;
 const selectedVoucher=vouchers.find(value=>value.code===voucherCode);const voucherAmountCents=Math.round(Number(voucherAmount.replace(",","."))*100);const voucherDialog=voucherOpen?<div className="sale-action-overlay"><section className="sale-action-dialog voucher-payment-dialog" role="dialog" aria-modal="true" aria-labelledby="voucher-payment-title"><header><h2 id="voucher-payment-title">{t("payment.voucher.title")}</h2><button type="button" aria-label={t("common.close")} onClick={()=>setVoucherOpen(false)}>×</button></header><label><span>{t("payment.voucher.code")}</span><select autoFocus value={voucherCode} onChange={event=>{const code=event.currentTarget.value;const voucher=vouchers.find(value=>value.code===code);setVoucherCode(code);if(voucher)setVoucherAmount((Math.min(totalCents,Math.round(Number(voucher.balance)*100))/100).toFixed(2));}}>{vouchers.map(voucher=><option key={voucher.code} value={voucher.code}>{voucher.code} · {Number(voucher.balance).toLocaleString(locale,{minimumFractionDigits:2,maximumFractionDigits:2})} €</option>)}</select></label><label><span>{t("payment.voucher.amount")}</span><input inputMode="decimal" value={voucherAmount} onChange={event=>setVoucherAmount(event.currentTarget.value)}/></label>{selectedVoucher&&<p>{t("payment.voucher.balance")}: {Number(selectedVoucher.balance).toLocaleString(locale,{minimumFractionDigits:2,maximumFractionDigits:2})} €</p>}<div className="sale-action-buttons"><button type="button" onClick={()=>setVoucherOpen(false)}>{t("common.cancel")}</button><button type="button" className="primary" disabled={voucherAmountCents<=0||voucherAmountCents>totalCents||voucherAmountCents>Math.round(Number(selectedVoucher?.balance??0)*100)} onClick={confirmVoucher}>{t("payment.voucher.apply")}</button></div></section></div>:null;
 const pendingFinalizeReady=Boolean(
  pendingFinalizeAuthorization
  &&effectiveCreatePendingAuthorization
  &&saleOperationAuthorizationComplete(
   effectiveCreatePendingAuthorization,
   pendingUsername,
   pendingPassword,
  )
  &&(!pendingFinalizeAuthorization.creditOverrideRequired||(
   effectiveCreditOverrideAuthorization
   &&creditOverrideReason.trim()
   &&saleOperationAuthorizationComplete(
    effectiveCreditOverrideAuthorization,
    creditOverrideUsername,
    creditOverridePassword,
   )
  )),
 );
 const pendingFinalizeDialog=pendingFinalizeAuthorization&&effectiveCreatePendingAuthorization?<div className="sale-action-overlay pending-sale-overlay" role="presentation">
  <section className="sale-action-dialog pending-sale-authorization-dialog" role="dialog" aria-modal="true" aria-labelledby="pending-finalize-authorization-title">
   <header>
    <h2 id="pending-finalize-authorization-title">{pendingFinalizeAuthorization.creditOverrideRequired?t("pendingSale.credit.overrideRequired"):t("pendingSale.authorization.pendingTitle")}</h2>
   </header>
   {effectiveCreatePendingAuthorization.mode!=="DIRECT"&&<>
    <h3>{t("pendingSale.authorization.pendingTitle")}</h3>
    <SaleOperationAuthorizationFields
     locale={locale}
     currentUsername={currentUsername}
     authorization={effectiveCreatePendingAuthorization}
     username={pendingUsername}
     password={pendingPassword}
     disabled={busy}
     autoFocus
     onUsernameChange={setPendingUsername}
     onPasswordChange={setPendingPassword}
    />
   </>}
   {pendingFinalizeAuthorization.creditOverrideRequired&&effectiveCreditOverrideAuthorization&&<>
    <label>
     <span>{t("pendingSale.credit.overrideReason")}</span>
     <textarea
      autoFocus={effectiveCreatePendingAuthorization.mode==="DIRECT"}
      maxLength={500}
      value={creditOverrideReason}
      disabled={busy}
      onChange={event=>setCreditOverrideReason(event.currentTarget.value)}
     />
    </label>
    <SaleOperationAuthorizationFields
     locale={locale}
     currentUsername={currentUsername}
     authorization={effectiveCreditOverrideAuthorization}
     username={creditOverrideUsername}
     password={creditOverridePassword}
     disabled={busy}
     onUsernameChange={setCreditOverrideUsername}
     onPasswordChange={setCreditOverridePassword}
    />
   </>}
   {error&&<p className="sale-action-error" role="alert">{error}</p>}
   <div className="sale-action-buttons">
    <button type="button" disabled={busy} onClick={()=>{
     setPendingFinalizeAuthorization(null);
     setPendingUsername("");
     setPendingPassword("");
     setCreditOverrideReason("");
     setCreditOverrideUsername("");
     setCreditOverridePassword("");
     setError("");
    }}>{t("common.cancel")}</button>
    <button type="button" className="primary" disabled={busy||!pendingFinalizeReady} onClick={()=>void submitPendingFinalizeAuthorization()}>{t("common.confirm")}</button>
   </div>
  </section>
 </div>:null;
 const presentation=checkoutPresentation(server?.status,server?.allocations.map(allocation=>allocation.status),safeRetry);
 const activePresentation=!unifiedCheckout&&testCashEnabled&&cashOpen&&presentation==="FINALIZE_RETRY"?"INDIVIDUAL_ACTIONS":presentation;
 if(!hydrationComplete&&hydrationFailed)return <div className="sale-payment-hydration-error" role="alert"><span>{t("payment.hydration.error")}</span><button type="button" onClick={()=>setHydrationRetry(value=>value+1)}>{t("payment.hydration.retry")}</button></div>;
 if(checkoutOpen){const panelSession:PaymentSession=server?map(server):{id:"new",totalCents:Math.abs(totalCents),direction:totalCents<0?"REFUND":totalCents===0?"ZERO":"SALE",status:"COLLECTING",allocations:[]};const isNewEmptyCollectingSession=server?.status==="COLLECTING"&&server.allocations.length===0&&entryHydratedSessionIdRef.current!==server.id;const checkoutAllowsAdd=!disabled&&(isNewEmptyCollectingSession||presentation==="INDIVIDUAL_ACTIONS"||presentation==="SPLIT");return <><PaymentAllocationPanel locale={locale} session={panelSession} providers={providers} manualCardEnabled={manual} cashEnabled={paymentMethods.cashActive} cardEnabled={paymentMethods.cardActive} voucherEnabled={paymentMethods.voucherActive} transferEnabled={paymentMethods.transferActive} manualCardRequiresReference={paymentMethods.cardRequiresReference} transferRequiresReference={paymentMethods.transferRequiresReference} vouchers={vouchers} interfaceMode={interfaceMode} initialMethod={initialMethod} customerSelected={customerSelected} pendingEnabled={pendingEnabled} checkoutDiscountCents={checkoutDiscountCents} memberBalanceCents={memberBalanceCents} memberBalanceAvailableCents={memberBalanceAvailableCents} memberWallet={memberWallet} voucherOnlyRefund={server?.voucherOnlyRefund??voucherOnlyRefund} busy={busy} error={error} allowAdd={checkoutAllowsAdd} acceptSubmitsCurrent acceptWithLockedIntegratedPayment onResolveVoucher={resolveVoucher} onAdd={(input,options)=>requestAllocation(input,options?.finalizeWhenCovered)} onQuery={id=>void query(id)} onManage={id=>void manage(id)} onClear={()=>void clearCheckout(false)} onClose={()=>void clearCheckout(true)} onAccept={()=>void retryFinish()} onDiscount={onDiscount} onMemberBalance={onMemberBalance} onMemberWallet={onMemberBalance?selection=>void applyMemberWalletSelection(selection):undefined}/>{pendingFinalizeDialog}{manualPaymentAuthorizationDialog}{reservationAuthorizationDialog}</>;}
 if(activePresentation==="INDIVIDUAL_ACTIONS"&&!checkoutOpen)return <>{showIndividualActions&&<IndividualPaymentActions locale={locale} disabled={!!disabled||totalCents<=0} busy={busy} cashEnabled={paymentMethods.cashActive} cardEnabled={paymentMethods.cardActive&&(manual||providers.length>0)} pendingEnabled={pendingEnabled} voucherEnabled={paymentMethods.voucherActive&&vouchers.length>0} onCash={unifiedCheckout?()=>openCheckout("CASH"):triggerCash} onCard={unifiedCheckout?()=>openCheckout("CARD"):triggerCard} onPending={unifiedCheckout?()=>openCheckout("PENDING"):triggerPending} onVoucher={unifiedCheckout?()=>openCheckout("VOUCHER"):openVoucher}/>} {cashDialog}{manualCardDialog}{voucherDialog}{pendingFinalizeDialog}{manualPaymentAuthorizationDialog}{error&&!cashOpen&&<p role="alert">{error}</p>}</>;
 if(presentation==="FINALIZE_RETRY")return <><div aria-busy={busy}><LegacyPaymentAllocationPanel locale={locale} session={map(server!)} providers={providers} manualCardEnabled={manual} onAdd={input=>requestAllocation(input)} onQuery={id=>void query(id)} onManage={id=>void manage(id)}/><button type="button" disabled={!canManuallyFinalizePayment(server!.status,busy)} onClick={()=>void retryFinish()}>{t("payment.split.finalize")}</button><button type="button" disabled={busy} onClick={()=>void cancel()}>{t("payment.split.cancelSession")}</button>{error&&<p role="alert">{error}</p>}{testCashStatus&&<p className="test-cash-session-status" role="status">{testCashStatus}</p>}{shouldOfferTestCashSession(testCashEnabled,server!.status,testCashRequired,terminal.terminalId)&&<button className="test-cash-session-button" type="button" disabled={busy} onClick={()=>void openTestCashSession()}>{busy?t("payment.testCash.opening"):t("payment.testCash.open")}</button>}</div>{cashDialog}{pendingFinalizeDialog}{manualPaymentAuthorizationDialog}</>;
 if(!server)return null;
 return <>
  <div aria-busy={busy}>
   <LegacyPaymentAllocationPanel
    locale={locale}
    session={map(server)}
    providers={providers}
    manualCardEnabled={manual}
    onAdd={input=>requestAllocation(input)}
    onQuery={id=>void query(id)}
    onManage={id=>void manage(id)}
    allowAdd={presentation!=="RECOVERY"}
   />
   <button type="button" disabled={busy} onClick={()=>void cancel()}>
    {t("payment.split.cancelSession")}
   </button>
   {server.status==="COMPENSATION_REQUIRED"&&<div role="alert">
    <p>{t("payment.split.compensationGuidance")}</p>
    {effectiveCompensationAuthorization&&<button
     type="button"
     onClick={()=>{
      setCompensationNote("");
      setCompensationUsername("");
      setCompensationPassword("");
      setCompensationDialog(true);
     }}
    >
     {t("payment.split.acknowledge")}
    </button>}
   </div>}
   {compensationDialog&&effectiveCompensationAuthorization&&<div
    role="dialog"
    aria-modal="true"
    aria-label={t("payment.split.compensationDialogTitle")}
   >
    <h3>{t("payment.split.compensationDialogTitle")}</h3>
    <label>
     {t("payment.split.compensationNote")}
     <textarea
      value={compensationNote}
      onChange={e=>setCompensationNote(e.currentTarget.value)}
      autoComplete="off"
     />
    </label>
    <SaleOperationAuthorizationFields
     locale={locale}
     currentUsername={currentUsername}
     authorization={effectiveCompensationAuthorization}
     username={compensationUsername}
     password={compensationPassword}
     disabled={busy}
     onUsernameChange={setCompensationUsername}
     onPasswordChange={setCompensationPassword}
    />
    <button
     type="button"
     disabled={!compensationNote.trim()||!saleOperationAuthorizationComplete(effectiveCompensationAuthorization,compensationUsername,compensationPassword)}
     onClick={()=>void acknowledge()}
    >
     {t("payment.split.confirm")}
    </button>
    <button
     type="button"
     onClick={()=>{
      setCompensationNote("");
      setCompensationUsername("");
      setCompensationPassword("");
      setCompensationDialog(false);
     }}
    >
     {t("payment.split.cancel")}
    </button>
   </div>}
   {manualPaymentAuthorizationDialog}
   {operation&&<PaymentOperationPanel
    t={t}
    operation={operation}
    events={events}
    capabilities={capabilities}
    permissions={permissions}
    voidAvailable={effectiveVoidAuthorization!==null}
    refundAvailable={effectiveRefundAuthorization!==null}
    onQuery={()=>void queryPaymentOperation(operation.id,token)
     .then(setOperation)
     .catch(e=>setError(e instanceof ApiError?e.message:t("payment.split.error.operationQuery")))}
    onVoid={effectiveVoidAuthorization?()=>{
     setAuthorizationUsername("");
     setAuthorizationPassword("");
     setAuthorization({
      kind:"VOID",
      authorization:effectiveVoidAuthorization,
      amount:"",
      options:[],
      lines:[],
     });
    }:undefined}
    onRefund={effectiveRefundAuthorization?()=>void openRefundAuthorization():undefined}
    onPrintReceipt={()=>void printPaymentReceipt(operation.id,token,terminal,getHardwareBridge())
     .catch(e=>setError(e instanceof ApiError?e.message:t("payment.split.error.print")))}
   />}
   {authorization&&<div
    role="dialog"
    aria-modal="true"
    aria-label={t(`payment.split.authorizationTitle.${authorization.kind}`)}
   >
    <h3>{t(`payment.split.authorizationTitle.${authorization.kind}`)}</h3>
    {authorization.kind==="REFUND"&&<>
     <label>
      {t("payment.split.refundAmount")}
      <input
       inputMode="decimal"
       value={authorization.amount}
       onChange={e=>setAuthorization({...authorization,amount:e.currentTarget.value})}
      />
     </label>
     <fieldset>
      <legend>{t("payment.split.refundLines")}</legend>
      {authorization.options.map(option=>{
       const selected=authorization.lines.find(line=>line.lineId===option.lineId);
       const serials=option.refundableSerialNumbers??[];
       return <div key={option.lineId}>
        <label>
         <input
          type="checkbox"
          checked={!!selected}
          onChange={e=>toggleRefundLine(option,e.currentTarget.checked)}
         />
         {option.code} · {option.name} ({t("payment.split.refundable")}: {String(option.refundableQuantity)})
        </label>
        {serials.map(serial=><small className="sale-line-serial" key={serial}>S/N: {serial}</small>)}
        {selected&&serials.length===0&&<input
         aria-label={`${option.name} ${t("payment.split.refundQuantity")}`}
         inputMode="decimal"
         value={selected.quantity}
         onChange={e=>updateRefundLine(option.lineId,e.currentTarget.value)}
        />}
       </div>;
      })}
     </fieldset>
    </>}
    <SaleOperationAuthorizationFields
     locale={locale}
     currentUsername={currentUsername}
     authorization={authorization.authorization}
     username={authorizationUsername}
     password={authorizationPassword}
     disabled={busy}
     autoFocus
     onUsernameChange={setAuthorizationUsername}
     onPasswordChange={setAuthorizationPassword}
    />
    <button
     type="button"
     onClick={()=>void authorize()}
     disabled={!saleOperationAuthorizationComplete(authorization.authorization,authorizationUsername,authorizationPassword)||(authorization.kind==="REFUND"&&(!authorization.amount||authorization.lines.some(line=>!line.quantity)))}
    >
     {t("payment.split.confirm")}
    </button>
    <button
     type="button"
     onClick={()=>{
      setAuthorizationUsername("");
      setAuthorizationPassword("");
      setAuthorization(null);
     }}
    >
     {t("payment.split.cancel")}
    </button>
   </div>}
   {error&&<p role="alert">{error}</p>}
  </div>
  {cashDialog}
  {pendingFinalizeDialog}
 </>;
});
