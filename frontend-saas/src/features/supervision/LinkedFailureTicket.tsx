import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import type { Credentials, SupportTicket } from "../../lib/types";
import { StatusPill } from "../../shared/ui";
import { repairSession } from "./repair-session";
import { TicketInterventionsPanel } from "./TicketInterventionsPanel";
import { TicketCommentComposer } from "../support/TicketCommentComposer";
import { useRepairLabels } from "./repair-labels";
export function LinkedFailureTicket({ credentials, companyId, ticketId, canManage }: {
 credentials:Credentials;companyId:string;ticketId:string;canManage:boolean;
}) {
 const f=useRepairLabels();const session=repairSession(credentials);
 const [ticket,setTicket]=useState<SupportTicket|null>(null);const [loading,setLoading]=useState(true);const [error,setError]=useState(false);
 const [commentsBlocked,setCommentsBlocked]=useState(canManage);
 const [revision,setRevision]=useState(0);const [busy,setBusy]=useState(false);const [interventionBusy,setInterventionBusy]=useState(false);
 useEffect(()=>{let active=true;setLoading(true);void api.supportTickets(credentials,companyId).then(rows=>{
   if(!active)return;const found=rows.find(row=>row.id===ticketId&&row.companyId===companyId)??null;setTicket(found);setError(!found);
 }).catch(()=>{if(active)setError(true);}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[credentials,companyId,ticketId,revision]);
 return <section aria-label={f("ticket")} className="content-section linked-failure-ticket">
   <p className="linked-ticket-scope">{f("ticketScope")}</p>{loading&&<p role="status">{f("loading")}</p>}{error&&<p role="alert">{f("ticketUnavailable")}</p>}
   <button className="secondary-button linked-ticket-refresh" type="button" disabled={busy||interventionBusy||loading} onClick={()=>setRevision(v=>v+1)}>{f("ticketReload")}</button>
   {ticket&&<><div className="linked-ticket-heading"><h5>{ticket.title}</h5><StatusPill status={f(ticket.status==="RESUELTO"?"ticketResolved":ticket.status==="EN_CURSO"?"ticketInProgress":"ticketOpen")} tone={ticket.status==="RESUELTO"?"ok":"warning"}/></div>
   {ticket.description&&<details className="linked-ticket-description"><summary>{f("ticketDetails")}</summary><p>{ticket.description}</p></details>}
   <TicketInterventionsPanel key={session.id+":"+ticketId} credentials={credentials} ticketId={ticketId} companyId={companyId} ticketStatus={ticket.status} canManage={canManage}
     blocked={loading||error||busy||commentsBlocked||session.tickets.has(ticketId)} refreshVersion={revision} onChanged={()=>setRevision(v=>v+1)} onBusyChange={setInterventionBusy}/>
   {canManage&&<TicketCommentComposer key={session.id+":comment:"+ticketId} credentials={credentials} ticketId={ticketId} disabled={loading||error||interventionBusy}
     onBusyChange={setBusy} onBlockedChange={setCommentsBlocked} refreshVersion={revision} onChanged={()=>setRevision(v=>v+1)}/>}
   </>}
 </section>;
}
