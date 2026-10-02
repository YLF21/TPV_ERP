const sessions = new WeakMap();
export function tenantCommentAuthor(username){return "tenant:"+username.toLowerCase();}
export function tenantConversationState(session, companyId, ticketId) {
  let conversations=sessions.get(session);if(!conversations){conversations=new Map();sessions.set(session,conversations);}
  const key=JSON.stringify([companyId,ticketId]);let state=conversations.get(key);
  if(!state){state={draft:"",pending:null,inFlight:null,version:0,listeners:new Set()};conversations.set(key,state);}
  return state;
}
function changed(state){state.version++;for(const notify of state.listeners)notify();}
export function subscribeConversation(state,notify){state.listeners.add(notify);return()=>state.listeners.delete(notify);}
export function editConversationDraft(state,value){if(state.pending||state.inFlight)return;state.draft=value;changed(state);}
export function validTenantComment(value,ticketId){return !!value&&typeof value.id==="string"&&!!value.id&&value.ticketId===ticketId&&typeof value.author==="string"&&typeof value.message==="string"&&typeof value.createdAt==="string"&&Number.isFinite(Date.parse(value.createdAt))&&(value.requestId==null||typeof value.requestId==="string");}
export function beginTenantComment(state,author,createId=()=>crypto.randomUUID()){
 if(state.inFlight||!state.draft.trim())return null;
 if(!state.pending)state.pending={requestId:createId(),message:state.draft.trim(),author,uncertain:false};
 const operation={write:state.pending};state.inFlight=operation;changed(state);return operation;
}
export function matchesTenantComment(comment,write,ticketId){return validTenantComment(comment,ticketId)&&comment.requestId===write.requestId&&comment.author===write.author&&comment.message===write.message;}
export function reconcileTenantComment(state,comments,ticketId){
 const write=state.pending;if(!write||!comments.some(comment=>matchesTenantComment(comment,write,ticketId)))return false;
 state.pending=null;state.draft="";changed(state);return true;
}
export function confirmTenantComment(state,operation,comment,ticketId){
 if(!matchesTenantComment(comment,operation.write,ticketId))return false;
 if(state.pending===operation.write){state.pending=null;state.draft="";changed(state);}
 return true;
}
export function rejectTenantComment(state,operation,definitive){
 if(state.pending!==operation.write)return;
 if(definitive)state.pending=null;else state.pending.uncertain=true;changed(state);
}
export function finishTenantComment(state,operation){if(state.inFlight!==operation)return;state.inFlight=null;changed(state);}
