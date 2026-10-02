import test from 'node:test';
import assert from 'node:assert/strict';
import { tenantCommentAuthor,tenantConversationState,editConversationDraft,beginTenantComment,reconcileTenantComment,confirmTenantComment,rejectTenantComment,finishTenantComment,validTenantComment } from '../src/features/tenant/support-conversation-state.mjs';
const receipt=(operation,changes={})=>({id:'comment-1',ticketId:'ticket-1',author:'client',message:operation.write.message,requestId:operation.write.requestId,createdAt:'2026-10-02T10:00:00Z',...changes});
test('drafts survive company and ticket switches but never another authenticated session',()=>{
 const session={};const one=tenantConversationState(session,'company-1','ticket-1');editConversationDraft(one,'Información del cliente');
 assert.equal(tenantConversationState(session,'company-2','ticket-1').draft,'');assert.equal(tenantConversationState(session,'company-1','ticket-2').draft,'');
 assert.equal(tenantConversationState(session,'company-1','ticket-1').draft,'Información del cliente');assert.equal(tenantConversationState({},'company-1','ticket-1').draft,'');
});
test('double click is blocked and uncertain retry retains immutable message and request id',()=>{
 const state=tenantConversationState({},'company-1','ticket-1');editConversationDraft(state,'  Revisar impresión  ');
 const first=beginTenantComment(state,'client',()=> 'request-1');assert.ok(first);assert.equal(beginTenantComment(state,'client'),null);
 rejectTenantComment(state,first,false);finishTenantComment(state,first);editConversationDraft(state,'Mensaje cambiado');assert.equal(state.draft,'  Revisar impresión  ');
 const retry=beginTenantComment(state,'client',()=> 'request-2');assert.equal(retry.write,first.write);assert.equal(retry.write.requestId,'request-1');assert.equal(retry.write.message,'Revisar impresión');
 assert.equal(confirmTenantComment(state,retry,receipt(retry),'ticket-1'),true);finishTenantComment(state,retry);assert.equal(state.pending,null);assert.equal(state.draft,'');
});
test('GET reconciliation confirms only matching request, author, ticket and text',()=>{
 const state=tenantConversationState({},'company-1','ticket-1');editConversationDraft(state,'已检查打印机');const operation=beginTenantComment(state,'client',()=> 'request-1');
 for(const changes of [{requestId:'wrong'},{author:'other'},{ticketId:'ticket-2'},{message:'other'},{createdAt:'invalid'}])assert.equal(reconcileTenantComment(state,[receipt(operation,changes)],'ticket-1'),false);
 assert.equal(reconcileTenantComment(state,[receipt(operation)],'ticket-1'),true);assert.equal(state.draft,'');assert.equal(beginTenantComment(state,'client'),null);finishTenantComment(state,operation);
});
test('stale completion cannot erase a later draft, request or in-flight operation',()=>{
 const state=tenantConversationState({},'company-1','ticket-1');editConversationDraft(state,'Primera respuesta');const old=beginTenantComment(state,'client',()=> 'request-1');
 assert.equal(reconcileTenantComment(state,[receipt(old)],'ticket-1'),true);finishTenantComment(state,old);
 editConversationDraft(state,'Nueva respuesta');const newer=beginTenantComment(state,'client',()=> 'request-2');
 confirmTenantComment(state,old,receipt(old),'ticket-1');rejectTenantComment(state,old,true);finishTenantComment(state,old);
 assert.equal(state.draft,'Nueva respuesta');assert.equal(state.pending,newer.write);assert.equal(state.inFlight,newer);
});
test('definitive rejection releases request while preserving draft; malformed responses cannot confirm',()=>{
 const state=tenantConversationState({},'company-1','ticket-1');editConversationDraft(state,'Se ha probado de nuevo');const operation=beginTenantComment(state,'client',()=> 'request-1');
 assert.equal(validTenantComment({id:'x'},'ticket-1'),false);assert.equal(confirmTenantComment(state,operation,receipt(operation,{requestId:null}),'ticket-1'),false);
 rejectTenantComment(state,operation,true);finishTenantComment(state,operation);assert.equal(state.pending,null);assert.equal(state.draft,'Se ha probado de nuevo');
 assert.equal(beginTenantComment(state,'client',()=> 'request-2').write.requestId,'request-2');
});

test('receipt author matches backend tenant prefix and lower-case username',()=>{
 const state=tenantConversationState({},'company-1','ticket-1');editConversationDraft(state,'Mensaje de cliente');
 const operation=beginTenantComment(state,tenantCommentAuthor('DemoUPPER'),()=> 'request-1');
 assert.equal(operation.write.author,'tenant:demoupper');
 assert.equal(confirmTenantComment(state,operation,receipt(operation,{author:'tenant:demoupper'}),'ticket-1'),true);
});
