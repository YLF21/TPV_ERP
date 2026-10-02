import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "../../lib/api";
import type { Credentials, SupportTicketComment } from "../../lib/types";
import { useI18n } from "../../i18n";
import { repairSession } from "../supervision/repair-session";
import { useRepairLabels } from "../supervision/repair-labels";

export function validTicketComment(value: SupportTicketComment | null | undefined, ticketId: string): value is SupportTicketComment {
  return !!value && typeof value.id === "string" && !!value.id && value.ticketId === ticketId
    && typeof value.author === "string" && typeof value.message === "string"
    && typeof value.createdAt === "string" && Number.isFinite(Date.parse(value.createdAt))
    && (value.requestId == null || typeof value.requestId === "string");
}
// A response lost after saving retains exactly the same request id, including across navigation.
export function TicketCommentComposer({credentials,ticketId,disabled=false,onChanged,onBusyChange,refreshVersion,onBlockedChange}: {
 credentials:Credentials;ticketId:string;disabled?:boolean;onChanged:()=>void;onBusyChange?:(busy:boolean)=>void;refreshVersion?:number;onBlockedChange?:(blocked:boolean)=>void;
}) {
 const commentId=useId();
 const {t}=useI18n();const f=useRepairLabels();const session=repairSession(credentials);
 const [draft,setDraft]=useState(()=>session.tickets.get(ticketId)?.value ?? session.commentDrafts.get(ticketId) ?? "");
 const [busy,setBusy]=useState(false);const lock=useRef(false);const [loading,setLoading]=useState(true);
 const [error,setError]=useState<string|null>(null);const [mustRefresh,setMustRefresh]=useState(false);const [saved,setSaved]=useState(false);
 const [revision,setRevision]=useState(0);const alive=useRef(true);const scope=useRef({credentials,ticketId});scope.current={credentials,ticketId};
 const current=()=>alive.current&&scope.current.credentials===credentials&&scope.current.ticketId===ticketId;
 const pending=session.tickets.get(ticketId);
 const blockedCallback=useRef(onBlockedChange);blockedCallback.current=onBlockedChange;
 useEffect(()=>{blockedCallback.current?.(loading||mustRefresh||busy||!!pending);},[loading,mustRefresh,busy,pending]);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
 useEffect(()=>{let active=true;setLoading(true);void api.supportTicketComments(credentials,ticketId).then(rows=>{
   if(!active||!current())return;
   if(!Array.isArray(rows)||!rows.every(row=>validTicketComment(row,ticketId)))throw new Error("Invalid comments");
   const write=session.tickets.get(ticketId);
   if(write&&rows.some(row=>row.requestId===write.requestId&&row.author===write.username&&row.message===write.value)){
     session.tickets.delete(ticketId);session.commentDrafts.delete(ticketId);setDraft("");setSaved(true);onChanged();
   }
   setMustRefresh(false);setError(null);
 }).catch(()=>{if(active&&current()){setMustRefresh(true);setError(f("ticketUnavailable"));}})
 .finally(()=>{if(active&&current())setLoading(false);});return()=>{active=false;};},[credentials,ticketId,revision,refreshVersion]);
 async function save(){
   if(disabled||lock.current||loading||mustRefresh||!draft.trim())return;
   const write=session.tickets.get(ticketId)??{kind:"comment" as const,value:draft.trim(),requestId:crypto.randomUUID(),username:credentials.username,uncertain:false};
   session.tickets.set(ticketId,write);lock.current=true;setBusy(true);onBusyChange?.(true);setSaved(false);setError(null);
   try{const added=await api.createSupportTicketComment(credentials,ticketId,write.value,write.requestId);
     if(!validTicketComment(added,ticketId)||added.requestId!==write.requestId||added.author!==write.username||added.message!==write.value)throw new Error("Unconfirmed comment");
     if(!current() || session.tickets.get(ticketId)!==write)return;
     session.tickets.delete(ticketId);
     session.commentDrafts.delete(ticketId);setDraft("");setSaved(true);onChanged();
   }catch(err){if(!current() || session.tickets.get(ticketId)!==write)return;
     const rejected=err instanceof ApiError&&[400,403,404,409,422].includes(err.status);
     if(rejected){if(session.tickets.get(ticketId)===write)session.tickets.delete(ticketId);setMustRefresh(true);}else write.uncertain=true;
     setError(f(rejected?"manualRejected":"manualSaveFailed"));
   }finally{lock.current=false;if(current()){setBusy(false);onBusyChange?.(false);}}
 }
 return <div className="support-comment-composer">
   {error&&<p role="alert">{error}</p>}{pending&&<p role="status">{f("ticketPending")}</p>}{saved&&<p role="status">{t("commentAdded")}</p>}
   <label htmlFor={commentId}>{t("comment")}</label><textarea id={commentId} className="control-input" rows={2} maxLength={4000} value={draft} disabled={disabled||busy||!!pending}
     onChange={event=>{setDraft(event.target.value);session.commentDrafts.set(ticketId,event.target.value);setSaved(false);}} />
   <div className="ticket-actions"><button className="secondary-button" type="button" disabled={disabled||busy||loading||mustRefresh||!draft.trim()} onClick={()=>void save()}>{pending?f("retryComment"):t("addComment")}</button>
   {refreshVersion===undefined&&(mustRefresh||pending)&&<button className="secondary-button" type="button" disabled={disabled||busy||loading} onClick={()=>setRevision(value=>value+1)}>{f("ticketReload")}</button>}</div>
 </div>;
}
