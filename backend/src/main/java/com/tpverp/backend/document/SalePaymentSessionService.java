package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.OperationalPermissionAuthorizationService.Authorization;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.security.sales.SaleOperationSecurityService;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SalePaymentSessionService {
 private final SalePaymentSessionRepository sessions; private final PosCashService sales; private final DocumentService documents; private final PosCardDocumentSnapshot snapshots;
 private final PaymentMethodRepository methods; private final CurrentOrganization organization; private final CurrentTerminal currentTerminal; private final CardTerminalConfigurationReader configurations; private final PaymentTerminalOperationService operations;
 private final CashPaymentRecorder cashPayments; private final TransactionOperations transactions; private final StorePaymentConfigurationRepository storePaymentConfigurations;
 private final SaleOperationSecurityService operationSecurity; private final AuditService audit;
 private CustomerPendingSaleService pendingSales;
 private VoucherService voucherService;
 @Autowired public SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,StorePaymentConfigurationRepository storePaymentConfigurations,SaleOperationSecurityService operationSecurity,AuditService audit,org.springframework.transaction.PlatformTransactionManager manager){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,storePaymentConfigurations,new TransactionTemplate(manager),operationSecurity,audit);}
 SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,null,new TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){return action.doInTransaction(null);}},null,null);}
 SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,StorePaymentConfigurationRepository storePaymentConfigurations){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,storePaymentConfigurations,new TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){return action.doInTransaction(null);}},null,null);}
 SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,SaleOperationSecurityService operationSecurity,AuditService audit){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,null,new TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){return action.doInTransaction(null);}},operationSecurity,audit);}
 private SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,StorePaymentConfigurationRepository storePaymentConfigurations,TransactionOperations transactions,SaleOperationSecurityService operationSecurity,AuditService audit){this.sessions=sessions;this.sales=sales;this.documents=documents;this.snapshots=snapshots;this.methods=methods;this.organization=organization;this.currentTerminal=currentTerminal;this.configurations=configurations;this.operations=operations;this.cashPayments=Objects.requireNonNull(cashPayments);this.storePaymentConfigurations=storePaymentConfigurations;this.transactions=transactions;this.operationSecurity=operationSecurity;this.audit=audit;}

 @Autowired void setVoucherService(VoucherService voucherService){this.voucherService=voucherService;}
 @Autowired void setCustomerPendingSaleService(CustomerPendingSaleService pendingSales){this.pendingSales=pendingSales;}

 @Transactional
 public SalePaymentSession reserve(
         UUID id,
         PosCashController.SaleRequest sale,
         Authentication auth) {
     var prepared = sales.prepareSale(sale, auth);
     var command = prepared.command();
     var hasDiscount = sale.checkoutDiscountAmount() != null
             && Money.euros(sale.checkoutDiscountAmount()).signum() > 0;
     var quoted = hasDiscount
             ? documents.quoteTicket(
                     command,
                     sale.promotionalCouponCode(),
                     sale.checkoutDiscountAmount(),
                     auth)
             : sale.promotionalCouponCode() == null
                     || sale.promotionalCouponCode().isBlank()
                     ? documents.quoteTicket(command, auth)
                     : documents.quoteTicket(command, sale.promotionalCouponCode(), auth);
     var requestHash = hash(sale, quoted.getTotal());
     var existing = sessions.findState(id);
     if (existing.isPresent()) {
         var current = scoped(existing.orElseThrow(), auth);
         if (!current.getRequestHash().equals(requestHash)) {
             throw new IllegalStateException(
                     "payment_session_idempotency_conflict");
         }
         return current;
     }
     var company = organization.currentCompany();
     var placeholder = methods.findAllByEmpresaIdOrderByNombre(company.getId())
             .stream()
             .filter(PaymentMethod::isActivo)
             .findFirst()
             .orElseThrow();
     var user = requireUser(auth);
     var terminal = currentTerminal.terminalId(auth);
     var snapshot = ApprovedCardTicketSnapshot.from(
             quoted, placeholder.getId());
     sales.authorizeSensitiveOperations(
             prepared,
             sale,
             quoted.getTotal(),
             auth,
             "PAYMENT_SESSION",
             id);
     return sessions.save(SalePaymentSession.reserve(
             id,
             quoted.getTiendaId(),
             terminal,
             user.getId(),
             requestHash,
             snapshots.serialize(snapshot),
             quoted.getTotal()));
 }
 @Transactional(readOnly=true) public SalePaymentSession get(UUID id,Authentication auth){return scoped(sessions.findState(id).orElseThrow(),auth);}
 @Transactional(readOnly=true) public Optional<SalePaymentSession> active(Authentication auth){var user=requireUser(auth);return sessions.findActive(organization.currentStore().getId(),currentTerminal.terminalId(auth),user.getId());}
 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String reference, Authentication auth) {
     return add(sessionId, allocationId, key, kind, amount, provider, reference,
             null, null, null, auth);
 }

 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String reference, BigDecimal delivered, BigDecimal change, String comment,
         Authentication auth) {
     return add(sessionId, allocationId, key, kind, amount, provider,
             kind == SalePaymentAllocationKind.VOUCHER ? reference : null,
             kind == SalePaymentAllocationKind.VOUCHER ? null : reference,
             delivered, change, comment, auth);
 }

 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String voucherCode, String reference, BigDecimal delivered, BigDecimal change,
         String comment, Authentication auth) {
     return add(
             sessionId,
             allocationId,
             key,
             kind,
             amount,
             provider,
             voucherCode,
             reference,
             delivered,
             change,
             comment,
             null,
             auth);
 }

 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String voucherCode, String reference, BigDecimal delivered, BigDecimal change,
         String comment, OperationAuthorizationRequest operationAuthorization,
         Authentication auth) {
     var normalized = Money.euros(amount);
     CardTerminalConfiguration config = null;
     if (kind == SalePaymentAllocationKind.INTEGRATED_CARD) {
         var state = get(sessionId, auth);
         config = configurations.required(state.getTerminalId());
         validateIntegratedConfiguration(config, state, provider);
     }
     if (kind == SalePaymentAllocationKind.VOUCHER) {
         requireVoucherService();
         if (voucherCode == null || voucherCode.isBlank()) {
             throw new IllegalArgumentException("voucher_code_required");
         }
         if (voucherService.availableBalance(voucherCode).compareTo(normalized) < 0) {
             throw new IllegalArgumentException("voucher_balance_insufficient");
         }
     }
     if (kind == SalePaymentAllocationKind.PENDING) {
         var snapshot = snapshots.deserialize(get(sessionId, auth).getSnapshot());
         if (snapshot.customerId() == null) {
             throw new IllegalArgumentException("pending_ticket_customer_required");
         }
     }
     var configuredMethod = activeMethod(kind);
	     if ((kind == SalePaymentAllocationKind.MANUAL_CARD
	             || kind == SalePaymentAllocationKind.TRANSFER)
	             && configuredMethod != null && configuredMethod.isRequiereReferencia()
                 && (reference == null || reference.isBlank())) {
         throw new IllegalArgumentException("payment_reference_required");
     }
     var requiredMethod = configuredMethod;
     var pending = transactions.execute(tx -> {
         var session = scoped(sessions.findLocked(sessionId).orElseThrow(), auth);
         var prior = session.getAllocations().stream()
                 .filter(a -> a.getIdempotencyKey().equals(key)).findFirst();
         if (prior.isPresent()) {
             var existing = prior.orElseThrow();
             if (existing.getKind() != kind
                     || existing.getAmount().compareTo(normalized) != 0
                     || !Objects.equals(existing.getProvider(), provider)
	                     || ((kind == SalePaymentAllocationKind.MANUAL_CARD
	                         || kind == SalePaymentAllocationKind.TRANSFER)
                         && !Objects.equals(existing.getReference(), normalize(reference)))
                     || (kind == SalePaymentAllocationKind.VOUCHER
                         && !Objects.equals(existing.getVoucherCode(), normalize(voucherCode)))
                     || !Objects.equals(existing.getDelivered(),
                         kind == SalePaymentAllocationKind.CASH
                                 ? delivered == null ? normalized : Money.euros(delivered)
                                 : null)
                     || !Objects.equals(existing.getComment(), normalize(comment))) {
                 throw new IllegalStateException("allocation_idempotency_conflict");
             }
             return session;
          }
          var authorization = authorizeManualPayment(
                  kind,
                  operationAuthorization,
                  auth);
          ensureNoUncertainIntegratedAllocation(session);
          if (kind == SalePaymentAllocationKind.CASH) {
              cashPayments.requireOpenSession(session.getTerminalId());
          }
	          if ((kind == SalePaymentAllocationKind.MANUAL_CARD
	                 || kind == SalePaymentAllocationKind.TRANSFER)
	                 && requiredMethod != null && requiredMethod.isRequiereReferencia()
	                 && (reference == null || reference.isBlank())) {
             throw new IllegalArgumentException("payment_reference_required");
         }
         if (kind == SalePaymentAllocationKind.VOUCHER
                 && session.getAllocations().stream().anyMatch(a ->
                     a.getKind() == SalePaymentAllocationKind.VOUCHER
                     && Objects.equals(a.getVoucherCode(), normalize(voucherCode)))) {
             throw new IllegalArgumentException("voucher_already_allocated");
         }
         if (kind == SalePaymentAllocationKind.PENDING
                 && session.getAllocations().stream().anyMatch(a ->
                     a.getKind() == SalePaymentAllocationKind.PENDING
                     && a.getStatus() == PaymentTerminalOperationStatus.APPROVED)) {
             throw new IllegalArgumentException("pending_amount_already_allocated");
         }
         var mode = kind == SalePaymentAllocationKind.MANUAL_CARD ? "MANUAL"
                 : kind == SalePaymentAllocationKind.INTEGRATED_CARD ? "INTEGRATED"
                 : kind == SalePaymentAllocationKind.VOUCHER ? "VOUCHER"
                 : kind == SalePaymentAllocationKind.TRANSFER ? "TRANSFER"
                 : kind == SalePaymentAllocationKind.PENDING ? "PENDING" : null;
         var allocation = session.addAllocation(allocationId, key, kind, normalized, provider, mode,
                 delivered, change, comment);
         if (kind == SalePaymentAllocationKind.VOUCHER) {
             allocation.assignVoucherCode(normalize(voucherCode));
         }
         if (kind == SalePaymentAllocationKind.CASH) {
             allocation.approve(null, null, null);
         } else if (kind == SalePaymentAllocationKind.MANUAL_CARD
                 || kind == SalePaymentAllocationKind.VOUCHER
                 || kind == SalePaymentAllocationKind.TRANSFER
                 || kind == SalePaymentAllocationKind.PENDING) {
             allocation.approve(null, normalize(reference), null);
         }
         var saved = sessions.save(session);
         auditManualPaymentAuthorization(
                 kind,
                 saved,
                 allocation,
                 authorization);
         return saved;
     });
     if (kind != SalePaymentAllocationKind.INTEGRATED_CARD
             || pending.getAllocations().stream()
                 .filter(a -> a.getIdempotencyKey().equals(key)).findFirst().orElseThrow()
                 .getStatus() != PaymentTerminalOperationStatus.PENDING) {
         return pending;
     }
     var result = operations.charge(allocationId,
             hashText(pending.getRequestHash() + "|" + key), normalized, config);
     return transactions.execute(tx -> {
         var session = scoped(sessions.findLocked(sessionId).orElseThrow(), auth);
         var allocation = session.getAllocations().stream()
                 .filter(value -> value.getIdempotencyKey().equals(key)).findFirst().orElseThrow();
         allocation.result(result.status(), allocationId, result.reference(),
                 result.authorization(), result.message());
         return sessions.save(session);
     });
 }
 @Transactional public SalePaymentSession query(UUID sessionId,UUID allocationId,Authentication auth){var session=scoped(sessions.findLocked(sessionId).orElseThrow(),auth);var a=session.getAllocations().stream().filter(x->x.getId().equals(allocationId)&&x.getKind()==SalePaymentAllocationKind.INTEGRATED_CARD).findFirst().orElseThrow();var op=operations.recover(a.getOperationId()==null?a.getId():a.getOperationId(),UUID.randomUUID());a.result(op.getStatus(),a.getId(),op.getExternalReference(),op.getAuthorizationCode(),null);return sessions.save(session);}
 @Transactional public Finalization finalizeSession(UUID id, Authentication auth) {
     return finalizeSession(id, null, null, null, auth);
 }
 @Transactional public Finalization finalizeSession(
         UUID id,
         String creditOverrideReason,
         String authorizerUsername,
         String authorizerPassword,
         Authentication auth) {
     return finalizeSession(
             id,
             creditOverrideReason,
             authorizerUsername,
             authorizerPassword,
             null,
             null,
             auth);
 }
 @Transactional public Finalization finalizeSession(
         UUID id,
         String creditOverrideReason,
         String authorizerUsername,
         String authorizerPassword,
         String creditOverrideAuthorizerUsername,
         String creditOverrideAuthorizerPassword,
         Authentication auth) {
     var session = scoped(sessions.findLocked(id).orElseThrow(), auth);
     if (session.getTicketId() != null) {
         var ticket = documents.loadForPrint(session.getTicketId());
         return new Finalization(session, TicketPrintView.from(ticket));
     }
     if (session.getStatus() != SalePaymentSessionStatus.COVERED) {
         throw new IllegalStateException("payment_session_not_finalizable");
     }
     if (!session.isCovered()) {
         throw new IllegalStateException("payment_session_not_covered");
     }
     var approved = session.getAllocations().stream()
             .filter(a -> a.getStatus() == PaymentTerminalOperationStatus.APPROVED).toList();
     var pendingAmount = approved.stream()
             .filter(a -> a.getKind() == SalePaymentAllocationKind.PENDING)
             .map(SalePaymentAllocation::getAmount)
             .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
     var snapshot = snapshots.deserialize(session.getSnapshot());
     CustomerPendingSaleService.PendingCreditAuthorization creditAuthorization = null;
     if (pendingAmount.signum() > 0) {
         creditAuthorization = Objects.requireNonNull(
                 pendingSales, "customer pending sale service")
                 .authorizePendingTicket(
                         Objects.requireNonNull(
                                 snapshot.customerId(),
                                 "pending ticket customer"),
                         snapshot.date(),
                         pendingAmount,
                         normalize(creditOverrideReason),
                         normalize(authorizerUsername),
                         authorizerPassword,
                         normalize(creditOverrideAuthorizerUsername),
                         creditOverrideAuthorizerPassword,
                         auth);
     }
     var payableAllocations = approved.stream()
             .filter(a -> a.getKind() != SalePaymentAllocationKind.PENDING).toList();
     approved.stream().filter(a -> a.getKind() == SalePaymentAllocationKind.INTEGRATED_CARD)
             .forEach(a -> operations.requireFinalizableApprovedCharge(a.getOperationId()));
     var company = organization.currentCompany();
     PaymentMethod cash = payableAllocations.stream().anyMatch(a -> a.getKind() == SalePaymentAllocationKind.CASH)
             ? methods.findByEmpresaIdAndNombreAndActivoTrue(company.getId(), "EFECTIVO").orElseThrow()
             : null;
     PaymentMethod card = payableAllocations.stream().anyMatch(a ->
             a.getKind() == SalePaymentAllocationKind.MANUAL_CARD
                     || a.getKind() == SalePaymentAllocationKind.INTEGRATED_CARD)
             ? methods.findByEmpresaIdAndNombreAndActivoTrue(company.getId(), "TARJETA").orElseThrow()
             : null;
     PaymentMethod voucher = payableAllocations.stream().anyMatch(a -> a.getKind() == SalePaymentAllocationKind.VOUCHER)
             ? methods.findByEmpresaIdAndNombreAndActivoTrue(company.getId(), "VALE").orElseThrow()
             : null;
     PaymentMethod transfer = payableAllocations.stream().anyMatch(a -> a.getKind() == SalePaymentAllocationKind.TRANSFER)
             ? methods.findByEmpresaIdAndNombreAndActivoTrue(company.getId(), "TRANSFERENCIA").orElseThrow()
             : null;
     var commands = new ArrayList<PaymentCommand>();
     for (int i = 0; i < payableAllocations.size(); i++) {
         var allocation = payableAllocations.get(i);
         var kind = allocation.getKind();
         var methodId = kind == SalePaymentAllocationKind.CASH ? cash.getId()
                 : kind == SalePaymentAllocationKind.VOUCHER ? voucher.getId()
                 : kind == SalePaymentAllocationKind.TRANSFER ? transfer.getId() : card.getId();
         commands.add(new PaymentCommand(
                 methodId,
                 allocation.getAmount(),
                 i == 0,
                 kind == SalePaymentAllocationKind.CASH ? allocation.getDelivered() : null,
                 kind == SalePaymentAllocationKind.CASH ? allocation.getChange() : null,
                 kind == SalePaymentAllocationKind.VOUCHER ? allocation.getVoucherCode() : null,
                 allocation.getReference(),
                 kind == SalePaymentAllocationKind.MANUAL_CARD ? PaymentCardMode.MANUAL
                         : kind == SalePaymentAllocationKind.INTEGRATED_CARD ? PaymentCardMode.INTEGRATED : null,
                 kind == SalePaymentAllocationKind.INTEGRATED_CARD
                         ? PaymentTerminalProvider.valueOf(allocation.getProvider()) : null,
                 kind == SalePaymentAllocationKind.INTEGRATED_CARD
                         ? PaymentTerminalOperationStatus.APPROVED : null,
                 allocation.getAuthorization(),
                 kind == SalePaymentAllocationKind.INTEGRATED_CARD ? session.getTerminalId() : null,
                 null,
                 allocation.getComment()));
     }
     var ticket = pendingAmount.signum() > 0
             ? documents.createPendingTicketFromSnapshot(
                     snapshot, commands, auth)
             : documents.createApprovedCardTicketFromSnapshot(
                     snapshot, commands, auth);
     if (creditAuthorization != null) {
         pendingSales.recordPendingTicketAuthorization(
                 id,
                 ticket,
                 snapshot.customerId(),
                 normalize(creditOverrideReason),
                 creditAuthorization);
     }
     session.finalizeWith(ticket.getId(), ticket.getNumero());
     for (int i = 0; i < payableAllocations.size(); i++) {
         var allocation = payableAllocations.get(i);
         if (allocation.getOperationId() != null) {
             operations.linkDocument(allocation.getOperationId(), ticket.getId(), ticket.getPagos().get(i).getId());
         }
     }
     var saved = sessions.save(session);
     return new Finalization(saved, TicketPrintView.from(ticket));
 }
 @Transactional public SalePaymentSession cancel(UUID id,Authentication auth){var s=scoped(sessions.findLocked(id).orElseThrow(),auth);s.cancel();return sessions.save(s);}
 @Transactional public SalePaymentSession discardSimulation(UUID id,String reason,Authentication auth){var normalizedReason=SimulatorDiscardReason.require(reason);var s=scoped(sessions.findLocked(id).orElseThrow(),auth);var configuration=configurations.required(s.getTerminalId());if(!configuration.terminalId().equals(s.getTerminalId())||!configuration.storeId().equals(s.getStoreId()))throw new IllegalArgumentException("payment_terminal_configuration_scope_mismatch");if(!configuration.testMode())throw new IllegalStateException("simulator_discard_requires_test_mode");s.discardSimulation(normalizedReason,requireUser(auth).getId());return sessions.save(s);}
 @Transactional public SalePaymentSession acknowledgeCompensation(UUID id,String note,String authorizerUsername,String authorizerPassword,Authentication auth){var s=scoped(sessions.findLocked(id).orElseThrow(),auth);var unresolved=s.getAllocations().stream().filter(a->a.getKind()==SalePaymentAllocationKind.INTEGRATED_CARD&&(a.getOperationId()!=null||a.requiresCompensationOnCancel())).anyMatch(this::hasUnresolvedDurableOperation);if(unresolved)throw new IllegalStateException("integrated_compensation_unresolved");if(operationSecurity==null||audit==null)throw new IllegalStateException("sale_operation_security_unavailable");var authorization=operationSecurity.authorize(SaleOperationCode.PAYMENT_COMPENSATION_ACK,authorizerUsername,authorizerPassword,auth);s.acknowledgeCompensation(note,authorization.authorizer().getId());var saved=sessions.save(s);var details=new LinkedHashMap<String,Object>();details.put("operationCode",SaleOperationCode.PAYMENT_COMPENSATION_ACK.name());details.put("paymentSessionId",s.getId().toString());details.put("operatorId",authorization.operator().getId().toString());details.put("operatorUsername",authorization.operator().getUserName());details.put("authorizerId",authorization.authorizer().getId().toString());details.put("authorizerUsername",authorization.authorizer().getUserName());details.put("delegated",authorization.delegated());audit.record("PAYMENT_COMPENSATION_ACKNOWLEDGED",AuditResult.EXITO,Map.copyOf(details));return saved;}
 private SalePaymentSession scoped(SalePaymentSession s,Authentication auth){if(!s.getStoreId().equals(organization.currentStore().getId())||!s.getTerminalId().equals(currentTerminal.terminalId(auth))||!s.getUserId().equals(requireUser(auth).getId()))throw new NoSuchElementException();return s;}
 private boolean hasUnresolvedDurableOperation(SalePaymentAllocation allocation){if(allocation.getOperationId()==null)return true;return operations.find(allocation.getOperationId()).map(operation->switch(operation.getStatus()){case CANCELLED,REFUNDED,DECLINED->false;default->true;}).orElse(true);}
 private void ensureNoUncertainIntegratedAllocation(SalePaymentSession session){var uncertain=session.getAllocations().stream().filter(a->a.getKind()==SalePaymentAllocationKind.INTEGRATED_CARD).anyMatch(a->{if(!PaymentLifecycleStatus.from(a.getStatus()).blocksAnotherCharge())return false;if(a.getOperationId()==null)return true;return operations.find(a.getOperationId()).map(operation->PaymentLifecycleStatus.from(operation).blocksAnotherCharge()).orElse(true);});if(uncertain)throw new IllegalStateException("integrated_payment_result_uncertain");}
 private Authorization authorizeManualPayment(
         SalePaymentAllocationKind kind,
         OperationAuthorizationRequest request,
         Authentication auth) {
     var code = switch (kind) {
         case MANUAL_CARD -> SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT;
         case TRANSFER -> SaleOperationCode.CONFIRM_TRANSFER_PAYMENT;
         default -> null;
     };
     if (code == null) {
         return null;
     }
     if (operationSecurity == null || audit == null) {
         throw new IllegalStateException("sale_operation_security_unavailable");
     }
     var credentials = request == null
             ? OperationAuthorizationRequest.empty()
             : request;
     return operationSecurity.authorize(
             code,
             credentials.authorizerUsername(),
             credentials.authorizerPassword(),
             auth);
 }
 private void auditManualPaymentAuthorization(
         SalePaymentAllocationKind kind,
         SalePaymentSession session,
         SalePaymentAllocation allocation,
         Authorization authorization) {
     if (authorization == null) {
         return;
     }
     var code = kind == SalePaymentAllocationKind.MANUAL_CARD
             ? SaleOperationCode.CONFIRM_MANUAL_CARD_PAYMENT
             : SaleOperationCode.CONFIRM_TRANSFER_PAYMENT;
     var details = new LinkedHashMap<String, Object>();
     details.put("operationCode", code.name());
     details.put("paymentSessionId", session.getId().toString());
     details.put("allocationId", allocation.getId().toString());
     details.put("paymentKind", kind.name());
     details.put("operatorId", authorization.operator().getId().toString());
     details.put("operatorUsername", authorization.operator().getUserName());
     details.put("authorizerId", authorization.authorizer().getId().toString());
     details.put("authorizerUsername", authorization.authorizer().getUserName());
     details.put("delegated", authorization.delegated());
     audit.record(
             "SALE_STANDARD_PAYMENT_AUTHORIZED",
             AuditResult.EXITO,
             Map.copyOf(details));
 }
 private void validateIntegratedConfiguration(CardTerminalConfiguration config,SalePaymentSession state,String provider){if(!config.enabled())throw new IllegalArgumentException("payment_terminal_configuration_not_enabled");if(config.mode()!=PaymentCardMode.INTEGRATED)throw new IllegalArgumentException("payment_terminal_configuration_not_integrated");if(config.provider()==null||config.provider()==PaymentTerminalProvider.NONE)throw new IllegalArgumentException("payment_terminal_provider_required");if(!config.terminalId().equals(state.getTerminalId())||!config.storeId().equals(state.getStoreId()))throw new IllegalArgumentException("payment_terminal_configuration_scope_mismatch");if(provider==null||!config.provider().name().equals(provider))throw new IllegalArgumentException("provider_not_configured");if(storePaymentConfigurations!=null){var rules=storePaymentConfigurations.findByStoreId(state.getStoreId()).orElse(null);if(rules!=null&&(!rules.isIntegratedCardEnabled()||!List.of(rules.getAllowedPaymentTerminalProviders().split(",")).contains(config.provider().name())))throw new IllegalArgumentException("payment_terminal_provider_not_allowed");}}
 private PaymentMethod activeMethod(SalePaymentAllocationKind kind){var name=switch(kind){case CASH->"EFECTIVO";case MANUAL_CARD,INTEGRATED_CARD->"TARJETA";case VOUCHER->"VALE";case TRANSFER->"TRANSFERENCIA";default->null;};var company=organization.currentCompany();return name==null||company==null?null:methods.findByEmpresaIdAndNombreAndActivoTrue(company.getId(),name).orElseThrow();}
 private void requireVoucherService(){if(voucherService==null)throw new IllegalStateException("voucher_service_unavailable");}
 private static String normalize(String value){return value==null||value.isBlank()?null:value.trim();}
 private static UserAccount requireUser(Authentication a){if(a.getPrincipal() instanceof UserAccount u)return u;throw new IllegalStateException("user_required");}
 private static String hash(PosCashController.SaleRequest sale,BigDecimal total){var coupon=sale.promotionalCouponCode();var internalComment=sale.internalComment()==null?"":sale.internalComment().trim();var hasOpenPrice=sale.lines().stream().anyMatch(line->line.openUnitPrice()!=null);var hasSerialNumbers=sale.lines().stream().anyMatch(line->line.serialNumbers()!=null&&!line.serialNumbers().isEmpty());var hasTemporaryNames=sale.lines().stream().anyMatch(line->line.temporaryName()!=null&&!line.temporaryName().isBlank());var canonical=new StringBuilder(hasTemporaryNames?"sale-payment-session-v7-temporary-name|":!internalComment.isEmpty()?"sale-payment-session-v6-internal-comment|":hasSerialNumbers?"sale-payment-session-v5-serials|":"sale-payment-session-v4-checkout-discount|").append(sale.customerId()).append('|');if(!internalComment.isEmpty())canonical.append(internalComment.length()).append(':').append(internalComment).append('|');canonical.append(coupon==null?"":coupon.trim()).append('|').append(sale.checkoutDiscountAmount()==null?"0.00":Money.euros(sale.checkoutDiscountAmount())).append('|').append(Money.euros(total));sale.lines().forEach(line->{canonical.append('|').append(line.productId()).append(':').append(line.quantity().stripTrailingZeros().toPlainString()).append(':').append(line.discount().stripTrailingZeros().toPlainString()).append(':').append(hasOpenPrice?(line.openUnitPrice()==null?"-":Money.euros(line.openUnitPrice()).toPlainString()):"-");if(hasSerialNumbers)canonical.append(':').append(PosCashService.canonicalSerialNumbers(line.serialNumbers()));if(hasTemporaryNames)canonical.append(':').append(PosCashService.canonicalText(line.temporaryName()));});return hashText(canonical.toString());}
 private static String hashText(String value){try{var md=MessageDigest.getInstance("SHA-256");return java.util.HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public record Finalization(SalePaymentSession session,TicketPrintView printTicket) {}
}
