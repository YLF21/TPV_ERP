import { FormEvent, useEffect, useId, useRef, useState, useSyncExternalStore } from "react";
import { tenantApi } from "../../lib/tenant-api";
import { ApiError } from "../../lib/api";
import type { Credentials, TenantSupportTicket, SupportTicketComment } from "../../lib/types";
import type { Notice } from "../../shared/types";
import { errorMessage, formatDate } from "../../shared/lib";
import { EmptyState } from "../../shared/ui";
import { useTenantLabels } from "./labels";
import { TenantTicketList } from "./TenantPortal";
import { tenantCommentAuthor, tenantConversationState, subscribeConversation, editConversationDraft, validTenantComment, beginTenantComment,
 confirmTenantComment, reconcileTenantComment, rejectTenantComment, finishTenantComment } from "./support-conversation-state.mjs";

export function TenantSupportConversation({ credentials, sessionCredentials=credentials, tickets, onNotice, revision }: {
 credentials: Credentials; sessionCredentials?:Credentials; tickets: TenantSupportTicket[]; onNotice:(notice:Notice)=>void; revision:number;
}) {
 const l=useTenantLabels();const [selectedId,setSelectedId]=useState("");
 const available=tickets.filter(ticket=>ticket.companyId===credentials.companyId);
 const ticket=available.find(item=>item.id===selectedId)??available[0];
 return <div className="tenant-conversation"><h3>{l("conversation")}</h3>{ticket?<>
  <label>{l("ticket")}<select aria-label={l("ticket")} className="control-input" value={ticket.id} onChange={event=>setSelectedId(event.target.value)}>
   {available.map(item=><option key={item.id} value={item.id}>{item.title}</option>)}
  </select></label><TenantTicketList tickets={[ticket]}/>
  <TenantCommentThread key={ticket.companyId+":"+ticket.id} credentials={credentials} sessionCredentials={sessionCredentials} ticketId={ticket.id} onNotice={onNotice} revision={revision}/>
 </>:<EmptyState text={l("empty")}/>}</div>;
}
function TenantCommentThread({credentials,sessionCredentials,ticketId,onNotice,revision}: {
 credentials:Credentials;sessionCredentials:Credentials;ticketId:string;onNotice:(notice:Notice)=>void;revision:number;
}){
 const l=useTenantLabels();const inputId=useId();
 const state=tenantConversationState(sessionCredentials,credentials.companyId??"",ticketId);
 useSyncExternalStore(notify=>subscribeConversation(state,notify),()=>state.version);
 const [comments,setComments]=useState<{context:typeof state;items:SupportTicketComment[]} | null>(null);const [loading,setLoading]=useState(true);
 const [error,setError]=useState<string|null>(null);const [mustRefresh,setMustRefresh]=useState(false);const [retry,setRetry]=useState(0);
 const alive=useRef(true);const scope=useRef({credentials,state});scope.current={credentials,state};const generation=useRef(0);
 const current=()=>alive.current&&scope.current.credentials===credentials&&scope.current.state===state;
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;generation.current++;};},[]);
 useEffect(()=>{
  const request=++generation.current;let cancelled=false;setLoading(true);
  const fresh=()=>!cancelled&&current()&&generation.current===request;
  void tenantApi.comments(credentials,ticketId).then(items=>{
   if(!fresh())return;
   if(!Array.isArray(items)||!items.every(item=>validTenantComment(item,ticketId)))throw new Error(l("invalidComments"));
   reconcileTenantComment(state,items,ticketId);setComments({context:state,items});setMustRefresh(false);setError(null);
  }).catch(failure=>{if(fresh()){setError(errorMessage(failure));setMustRefresh(true);}})
  .finally(()=>{if(fresh())setLoading(false);});return()=>{cancelled=true;};
 },[credentials,state,ticketId,revision,retry]);
 async function send(event:FormEvent){
  event.preventDefault();if(loading||mustRefresh)return;
  const operation=beginTenantComment(state,tenantCommentAuthor(credentials.username));if(!operation)return;
  generation.current++;setError(null);
  try{
   const added=await tenantApi.comment(credentials,ticketId,operation.write.message,operation.write.requestId);
   if(!confirmTenantComment(state,operation,added,ticketId))throw new Error(l("unconfirmed"));
   if(!current())return;
   setComments(previous=>({context:state,items:[...(previous?.context===state?previous.items.filter(item=>item.id!==added.id):[]),added]}));onNotice({type:"success",text:l("sent")});
  }catch(failure){
   const rejected=failure instanceof ApiError&&[400,403,404,409,422].includes(failure.status);
   rejectTenantComment(state,operation,rejected);
   if(current()){setMustRefresh(rejected);setError(rejected?errorMessage(failure):l("unconfirmed"));}
  }finally{finishTenantComment(state,operation);}
 }
 const busy=!!state.inFlight;
 return <>
  {loading&&<p role="status">{l("loading")}</p>}{error&&<p role="alert">{error}</p>}
  {state.pending&&<p role="status">{l("pendingMessage")}</p>}
  {(error||state.pending)&&<button type="button" className="secondary-button" disabled={busy||loading} onClick={()=>setRetry(value=>value+1)}>{l("refreshConversation")}</button>}
  <div className="tenant-comments" aria-live="polite">{(comments?.context===state?comments.items:[]).map(comment=><article className="ticket-card" key={comment.id}><strong>{comment.author}</strong><small>{formatDate(comment.createdAt)}</small><p style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{comment.message}</p></article>)}</div>
  <form onSubmit={send}><label htmlFor={inputId}>{l("message")}</label><textarea id={inputId} className="control-input" rows={3} value={state.draft} onChange={event=>editConversationDraft(state,event.target.value)} required maxLength={4000} disabled={busy||!!state.pending}/>
   <button type="submit" className="primary-button" disabled={busy||loading||mustRefresh||!state.draft.trim()}>{l(state.pending?"retryMessage":"send")}</button>
  </form>
 </>;
}
