package com.tpverp.backend.document;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.cash.CashPaymentRecorder;
import com.tpverp.backend.control.ControlAlertDetectionService;
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
 private StoreReturnPolicyService returnPolicy;
 private RefundSettlementRecorder refundSettlements;
 private PaymentTerminalOperationsService refundTerminalOperations;
 private ControlAlertDetectionService controlAlerts;
 private RefundTenderRepository refundTenders;
 private TicketReturnValuationService returnValuations;
 private DocumentPaymentRepository documentPayments;
 @Autowired public SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,StorePaymentConfigurationRepository storePaymentConfigurations,SaleOperationSecurityService operationSecurity,AuditService audit,org.springframework.transaction.PlatformTransactionManager manager){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,storePaymentConfigurations,new TransactionTemplate(manager),operationSecurity,audit);}
 SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,null,new TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){return action.doInTransaction(null);}},null,null);}
 SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,StorePaymentConfigurationRepository storePaymentConfigurations){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,storePaymentConfigurations,new TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){return action.doInTransaction(null);}},null,null);}
 SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,SaleOperationSecurityService operationSecurity,AuditService audit){this(sessions,sales,documents,snapshots,methods,organization,currentTerminal,configurations,operations,cashPayments,null,new TransactionOperations(){public <T>T execute(org.springframework.transaction.support.TransactionCallback<T> action){return action.doInTransaction(null);}},operationSecurity,audit);}
 private SalePaymentSessionService(SalePaymentSessionRepository sessions,PosCashService sales,DocumentService documents,PosCardDocumentSnapshot snapshots,PaymentMethodRepository methods,CurrentOrganization organization,CurrentTerminal currentTerminal,CardTerminalConfigurationReader configurations,PaymentTerminalOperationService operations,CashPaymentRecorder cashPayments,StorePaymentConfigurationRepository storePaymentConfigurations,TransactionOperations transactions,SaleOperationSecurityService operationSecurity,AuditService audit){this.sessions=sessions;this.sales=sales;this.documents=documents;this.snapshots=snapshots;this.methods=methods;this.organization=organization;this.currentTerminal=currentTerminal;this.configurations=configurations;this.operations=operations;this.cashPayments=Objects.requireNonNull(cashPayments);this.storePaymentConfigurations=storePaymentConfigurations;this.transactions=transactions;this.operationSecurity=operationSecurity;this.audit=audit;}

 @Autowired void setVoucherService(VoucherService voucherService){this.voucherService=voucherService;}
 @Autowired void setCustomerPendingSaleService(CustomerPendingSaleService pendingSales){this.pendingSales=pendingSales;}
 @Autowired void setStoreReturnPolicyService(StoreReturnPolicyService returnPolicy){this.returnPolicy=returnPolicy;}
 @Autowired void setRefundSettlementRecorder(RefundSettlementRecorder refundSettlements){this.refundSettlements=refundSettlements;}
 @Autowired void setRefundTerminalOperations(PaymentTerminalOperationsService refundTerminalOperations){this.refundTerminalOperations=refundTerminalOperations;}
 @Autowired void setControlAlerts(ControlAlertDetectionService controlAlerts){this.controlAlerts=controlAlerts;}
 @Autowired void setRefundTenders(RefundTenderRepository refundTenders){this.refundTenders=refundTenders;}
 @Autowired void setTicketReturnValuationService(TicketReturnValuationService returnValuations){this.returnValuations=returnValuations;}
 @Autowired void setDocumentPaymentRepository(DocumentPaymentRepository documentPayments){this.documentPayments=documentPayments;}

 @Transactional
 public SalePaymentSession reserve(
         UUID id,
         PosCashController.SaleRequest sale,
         Authentication auth) {
     // Resolve an existing idempotency key before rebuilding the quote. A historical
     // replay changes which ticket is "previous" after completion, but retrying the
     // same session must still return its original immutable result.
     var existing = sessions.findState(id);
     if (existing.isPresent()) {
         var current = scoped(existing.orElseThrow(), auth);
         // The request hash is built from the signed document total. Refund
         // sessions expose getTotal() as the positive amount still to settle,
         // while their immutable quote used the negative document total.
         var existingRequestHash = hash(sale, current.getDocumentTotal());
         if (!current.getRequestHash().equals(existingRequestHash)) {
             throw new IllegalStateException(
                     "payment_session_idempotency_conflict");
         }
         return current;
     }
     var prepared = sales.prepareSale(sale, auth);
     var command = prepared.command();
     var quoted = sales.quotePreparedSale(prepared, sale, auth);
     sales.validateQuoteFingerprint(sale, quoted);
     var requestHash = hash(sale, quoted.getTotal());
     var company = organization.currentCompany();
     var user = requireUser(auth);
     var terminal = currentTerminal.terminalId(auth);
     var active = sessions.findActive(quoted.getTiendaId(), terminal, user.getId());
     if (active.isPresent()) {
         var current = scoped(active.orElseThrow(), auth);
         if (!current.getRequestHash().equals(requestHash)) {
             throw new IllegalStateException("payment_session_active_conflict");
         }
         return current;
     }
     var placeholder = methods.findAllByEmpresaIdOrderByNombre(company.getId())
             .stream()
             .filter(PaymentMethod::isActivo)
             .filter(method -> !PaymentMethodService.EXCHANGE_COMPENSATION_METHOD
                     .equals(method.getNombre()))
             .findFirst()
             .orElseThrow();
     var snapshot = sales.snapshot(quoted, placeholder.getId(), prepared);
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
              null,
              null,
              auth);
 }

 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String voucherCode, String reference, BigDecimal delivered, BigDecimal change,
          String comment, OperationAuthorizationRequest operationAuthorization,
          Authentication auth) {
      return add(sessionId, allocationId, key, kind, amount, provider, voucherCode,
              reference, delivered, change, comment, operationAuthorization, null, null, auth);
  }

 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String voucherCode, String reference, BigDecimal delivered, BigDecimal change,
         String comment, OperationAuthorizationRequest operationAuthorization,
         OperationAuthorizationRequest refundPolicyAuthorization,
         Authentication auth) {
     return add(sessionId, allocationId, key, kind, amount, provider, voucherCode,
             reference, delivered, change, comment, operationAuthorization,
             refundPolicyAuthorization, null, auth);
 }

 public SalePaymentSession add(UUID sessionId, UUID allocationId, String key,
         SalePaymentAllocationKind kind, BigDecimal amount, String provider,
         String voucherCode, String reference, BigDecimal delivered, BigDecimal change,
         String comment, OperationAuthorizationRequest operationAuthorization,
         OperationAuthorizationRequest refundPolicyAuthorization,
         OperationAuthorizationRequest refundTenderAuthorization,
         Authentication auth) {
     var normalized = Money.euros(amount);
      SalePaymentSession state = null;
      CardTerminalConfiguration config = null;
      if (kind == SalePaymentAllocationKind.INTEGRATED_CARD) {
          state = get(sessionId, auth);
          config = configurations.required(state.getTerminalId());
          validateIntegratedConfiguration(config, state, provider);
      }
      if (kind == SalePaymentAllocationKind.PENDING) {
          state = get(sessionId, auth);
          var snapshot = snapshots.deserialize(state.getSnapshot());
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
         var refund = session.getDirection() == SalePaymentSessionDirection.REFUND;
         if (refund && (kind == SalePaymentAllocationKind.PENDING
                 || kind == SalePaymentAllocationKind.TRANSFER)) {
             throw new IllegalArgumentException("refund_payment_method_not_allowed");
         }
         if (kind == SalePaymentAllocationKind.VOUCHER && !refund) {
             requireVoucherService();
             if (voucherCode == null || voucherCode.isBlank()) {
                 throw new IllegalArgumentException("voucher_code_required");
             }
             if (voucherService.availableBalance(voucherCode).compareTo(normalized) < 0) {
                 throw new IllegalArgumentException("voucher_balance_insufficient");
             }
         }
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
           var refundAuthorization = authorizeRefundPolicyOverride(
                   session, kind, refundPolicyAuthorization, auth);
           Authorization refundTenderAuthorizationResult = null;
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
          if (kind == SalePaymentAllocationKind.VOUCHER && !refund
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
         DocumentPayment originalRefundPayment = null;
         if (refund && kind != SalePaymentAllocationKind.VOUCHER) {
             originalRefundPayment = resolveMatchingOriginalPayment(
                     session, kind, normalized).orElse(null);
             if (originalRefundPayment == null) {
                 if (kind == SalePaymentAllocationKind.INTEGRATED_CARD) {
                     throw new IllegalArgumentException(
                             "original_card_payment_not_available");
                 }
                 refundTenderAuthorizationResult = authorizeRefundTenderOverride(
                         refundTenderAuthorization, auth);
                 originalRefundPayment = resolveAnyOriginalPayment(session, normalized);
             }
         }
         var mode = kind == SalePaymentAllocationKind.MANUAL_CARD ? "MANUAL"
                 : kind == SalePaymentAllocationKind.INTEGRATED_CARD ? "INTEGRATED"
                 : kind == SalePaymentAllocationKind.VOUCHER ? "VOUCHER"
                 : kind == SalePaymentAllocationKind.TRANSFER ? "TRANSFER"
                 : kind == SalePaymentAllocationKind.PENDING ? "PENDING" : null;
          var allocation = session.addAllocation(allocationId, key, kind, normalized, provider, mode,
                  delivered, change, comment);
          if (kind == SalePaymentAllocationKind.VOUCHER && !refund) {
              allocation.assignVoucherCode(normalize(voucherCode));
          }
          if (originalRefundPayment != null) {
              allocation.assignOriginalPaymentId(originalRefundPayment.getId());
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
          auditRefundPolicyOverride(saved, allocation, refundAuthorization, auth);
          auditRefundTenderOverride(
                  saved, allocation, refundTenderAuthorizationResult, auth);
          return saved;
     });
     if (kind != SalePaymentAllocationKind.INTEGRATED_CARD
             || pending.getAllocations().stream()
                 .filter(a -> a.getIdempotencyKey().equals(key)).findFirst().orElseThrow()
                 .getStatus() != PaymentTerminalOperationStatus.PENDING) {
         return pending;
     }
      PaymentTerminalResult result;
      if (pending.getDirection() == SalePaymentSessionDirection.REFUND) {
          var allocation = pending.getAllocations().stream()
                  .filter(value -> value.getIdempotencyKey().equals(key)).findFirst().orElseThrow();
          var originalOperation = requireRefundTerminalOperations()
                  .findByDocumentPaymentId(allocation.getOriginalPaymentId())
                  .orElseThrow(() -> new IllegalArgumentException(
                          "original_card_terminal_operation_not_found"));
          var returned = requireRefundTerminalOperations().refundPaymentOnly(
                  originalOperation.getId(), allocationId, key, normalized);
          result = new PaymentTerminalResult(
                  returned.getStatus(), "REFUND", returned.getExternalReference(),
                  returned.getAuthorizationCode(), null);
      } else {
          result = operations.charge(allocationId,
                  hashText(pending.getRequestHash() + "|" + key), normalized, config);
      }
     return transactions.execute(tx -> {
         var session = scoped(sessions.findLocked(sessionId).orElseThrow(), auth);
         var allocation = session.getAllocations().stream()
                 .filter(value -> value.getIdempotencyKey().equals(key)).findFirst().orElseThrow();
         allocation.result(result.status(), allocationId, result.reference(),
                 result.authorization(), result.message());
         return sessions.save(session);
     });
 }
 @Transactional public SalePaymentSession query(UUID sessionId,UUID allocationId,Authentication auth){var session=scoped(sessions.findLocked(sessionId).orElseThrow(),auth);var a=session.getAllocations().stream().filter(x->x.getId().equals(allocationId)&&x.getKind()==SalePaymentAllocationKind.INTEGRATED_CARD).findFirst().orElseThrow();if(session.getDirection()==SalePaymentSessionDirection.REFUND){var op=requireRefundTerminalOperations().query(a.getOperationId()==null?a.getId():a.getOperationId());a.result(op.getStatus(),a.getId(),op.getExternalReference(),op.getAuthorizationCode(),null);}else{var op=operations.recover(a.getOperationId()==null?a.getId():a.getOperationId(),UUID.randomUUID());a.result(op.getStatus(),a.getId(),op.getExternalReference(),op.getAuthorizationCode(),null);}return sessions.save(session);}
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
         return new Finalization(
                 session,
                 documents.loadRenderedTicketPrintView(session.getTicketId()),
                 issuedVoucherFor(documents.loadForPrint(session.getTicketId())));
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
     approved.stream().filter(a -> a.getKind() == SalePaymentAllocationKind.INTEGRATED_CARD
             && session.getDirection() == SalePaymentSessionDirection.SALE)
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
     if (session.getDirection() == SalePaymentSessionDirection.SALE) for (int i = 0; i < payableAllocations.size(); i++) {
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
      CommercialDocument ticket;
      TicketPrintView printTicket;
      IssuedVoucher issuedVoucher = null;
      if (session.getDirection() == SalePaymentSessionDirection.REFUND) {
         var payouts = approved.stream().map(allocation ->
                 new RefundSettlementRecorder.TenderCommand(
                         allocation.getKind() == SalePaymentAllocationKind.CASH
                                 ? RefundTenderType.CASH
                                 : allocation.getKind() == SalePaymentAllocationKind.VOUCHER
                                         ? RefundTenderType.VOUCHER
                                         : allocation.getKind() == SalePaymentAllocationKind.TRANSFER
                                                 ? RefundTenderType.TRANSFER
                                         : RefundTenderType.CARD,
                         allocation.getAmount(),
                         allocation.getOriginalPaymentId(),
                         allocation.getKind() == SalePaymentAllocationKind.INTEGRATED_CARD
                                 ? allocation.getOperationId() : null,
                         allocation.getReference()))
                 .toList();
         var sourceTicketId = refundSourceTicketId(snapshot);
         var selections = refundSelections(snapshot);
         var valuation = Objects.requireNonNull(
                 returnValuations, "return valuation service")
                 .value(documents.find(sourceTicketId), selections.stream()
                         .collect(java.util.stream.Collectors.toMap(
                                 PaymentTerminalRefundLineSelection::lineId,
                                 PaymentTerminalRefundLineSelection::quantity,
                                 BigDecimal::add,
                                 LinkedHashMap::new)));
         if (Money.euros(valuation.refundableAmount()).compareTo(session.getTotal()) != 0) {
             throw new IllegalStateException("refund_checkout_valuation_changed");
         }
         var firstCardOperation = payouts.stream()
                 .filter(value -> value.type() == RefundTenderType.CARD)
                 .map(RefundSettlementRecorder.TenderCommand::terminalOperationId)
                 .filter(Objects::nonNull)
                 .findFirst().orElse(null);
         ticket = documents.createApprovedReturn(
                 session.getId(), sourceTicketId, session.getTotal(), selections,
                 firstCardOperation, valuation, auth);
         Objects.requireNonNull(refundSettlements, "refund settlement recorder")
                 .recordExistingNegativeTicket(ticket, payouts, auth);
         var voucherAmount = payouts.stream()
                 .filter(value -> value.type() == RefundTenderType.VOUCHER)
                 .map(RefundSettlementRecorder.TenderCommand::amount)
                 .reduce(BigDecimal.ZERO, BigDecimal::add);
         if (voucherAmount.signum() > 0) {
              var generatedVoucher = requireVoucherService()
                      .issueOrFindFromNegativeTicket(ticket, voucherAmount);
              issuedVoucher = IssuedVoucher.from(generatedVoucher, ticket.getNumero());
         }
         printTicket = documents.renderTicketPrintView(
                 ticket, documents.ticketPrintView(ticket));
     } else if (hasReturnLines(snapshot)) {
         var sourceTicketId = refundSourceTicketId(snapshot);
         var selections = refundSelections(snapshot);
         var valuation = Objects.requireNonNull(
                 returnValuations, "return valuation service")
                 .value(documents.find(sourceTicketId), selections.stream()
                         .collect(java.util.stream.Collectors.toMap(
                                 PaymentTerminalRefundLineSelection::lineId,
                                 PaymentTerminalRefundLineSelection::quantity,
                                 BigDecimal::add,
                                 LinkedHashMap::new)));
         var refundAmount = Money.euros(valuation.refundableAmount());
         var refund = documents.createApprovedReturn(
                 session.getId(), sourceTicketId, refundAmount, selections,
                 null, valuation, auth);
         if (hasPositiveSaleLines(snapshot)) {
             if (refundAmount.signum() > 0) {
                 var compensation = methods.findByEmpresaIdAndNombreAndActivoTrue(
                                 company.getId(), PaymentMethodService.EXCHANGE_COMPENSATION_METHOD)
                         .orElseThrow(() -> new IllegalStateException(
                                 "exchange_compensation_method_unavailable"));
                 commands.add(new PaymentCommand(
                         compensation.getId(), refundAmount,
                         commands.isEmpty(), null, null, null,
                         refund.getNumero(), null, null, null, null, null, null,
                         "Compensacion de " + refund.getNumero()));
             }
             ticket = pendingAmount.signum() > 0
                     ? documents.createPendingExchangeSaleFromSnapshot(
                             snapshot, commands, refund, auth)
                     : documents.createApprovedExchangeSaleFromSnapshot(
                             snapshot, commands, refund, auth);
             if (refundAmount.signum() > 0) {
                 Objects.requireNonNull(refundSettlements, "refund settlement recorder")
                         .recordExistingNegativeTicket(
                                 refund,
                                 List.of(new RefundSettlementRecorder.TenderCommand(
                                         RefundTenderType.EXCHANGE, refundAmount,
                                         null, null, ticket.getNumero())),
                                 auth);
             }
             printTicket = documents.ticketPrintViewFromExchange(ticket, refund);
         } else {
             ticket = refund;
             printTicket = documents.renderTicketPrintView(
                     refund, documents.ticketPrintView(refund));
         }
     } else {
         ticket = pendingAmount.signum() > 0
                 ? documents.createPendingTicketFromSnapshot(snapshot, commands, auth)
                 : documents.createApprovedCardTicketFromSnapshot(snapshot, commands, auth);
         printTicket = documents.renderTicketPrintView(
                 ticket, documents.ticketPrintView(ticket));
     }
     if (issuedVoucher == null) {
         issuedVoucher = issuedVoucherFor(ticket);
     }
      sales.completeTemporaryPriceAuthorizations("PAYMENT_SESSION", id);
     if (creditAuthorization != null) {
         pendingSales.recordPendingTicketAuthorization(
                 id,
                 ticket,
                 snapshot.customerId(),
                 normalize(creditOverrideReason),
                 creditAuthorization);
     }
     session.finalizeWith(ticket.getId(), ticket.getNumero());
     if (session.getDirection() == SalePaymentSessionDirection.SALE) for (int i = 0; i < payableAllocations.size(); i++) {
         var allocation = payableAllocations.get(i);
         if (allocation.getOperationId() != null) {
             operations.linkDocument(allocation.getOperationId(), ticket.getId(), ticket.getPagos().get(i).getId());
         }
     }
     var saved = sessions.save(session);
     return new Finalization(saved, printTicket, issuedVoucher);
 }
 @Transactional public SalePaymentSession cancel(UUID id,Authentication auth){var s=scoped(sessions.findLocked(id).orElseThrow(),auth);s.cancel();sales.releaseTemporaryPriceAuthorizations("PAYMENT_SESSION",id);return sessions.save(s);}
 @Transactional public SalePaymentSession discardSimulation(UUID id,String reason,Authentication auth){var normalizedReason=SimulatorDiscardReason.require(reason);var s=scoped(sessions.findLocked(id).orElseThrow(),auth);var configuration=configurations.required(s.getTerminalId());if(!configuration.terminalId().equals(s.getTerminalId())||!configuration.storeId().equals(s.getStoreId()))throw new IllegalArgumentException("payment_terminal_configuration_scope_mismatch");if(!configuration.testMode())throw new IllegalStateException("simulator_discard_requires_test_mode");s.discardSimulation(normalizedReason,requireUser(auth).getId());sales.releaseTemporaryPriceAuthorizations("PAYMENT_SESSION",id);return sessions.save(s);}
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
 private Authorization authorizeRefundPolicyOverride(
         SalePaymentSession session,
         SalePaymentAllocationKind kind,
         OperationAuthorizationRequest request,
         Authentication auth) {
     if (session.getDirection() != SalePaymentSessionDirection.REFUND
             || kind == SalePaymentAllocationKind.VOUCHER) {
         return null;
     }
     var snapshot = snapshots.deserialize(session.getSnapshot());
     if (snapshot.lines().stream().anyMatch(line ->
             line.returnSourceType() == TicketReturnService.ReturnSourceType.GIFT_RECEIPT)) {
         throw new IllegalArgumentException("gift_receipt_money_refund_not_allowed");
     }
     if (returnPolicy == null
             || returnPolicy.policy() == StoreReturnPolicy.REFUND_ALLOWED) {
         return null;
     }
     if (operationSecurity == null || audit == null) {
         throw new IllegalStateException("sale_operation_security_unavailable");
     }
     var credentials = request == null
             ? OperationAuthorizationRequest.empty()
             : request;
     return operationSecurity.authorize(
             SaleOperationCode.REFUND_POLICY_OVERRIDE,
             credentials.authorizerUsername(),
             credentials.authorizerPassword(),
             auth);
 }
 private void auditRefundPolicyOverride(
         SalePaymentSession session,
         SalePaymentAllocation allocation,
         Authorization authorization,
         Authentication auth) {
     if (authorization == null) return;
     var details = new LinkedHashMap<String, Object>();
     details.put("operationCode", SaleOperationCode.REFUND_POLICY_OVERRIDE.name());
     details.put("paymentSessionId", session.getId().toString());
     details.put("allocationId", allocation.getId().toString());
     details.put("paymentKind", allocation.getKind().name());
     details.put("amount", allocation.getAmount());
     details.put("operatorId", authorization.operator().getId().toString());
     details.put("operatorUsername", authorization.operator().getUserName());
     details.put("authorizerId", authorization.authorizer().getId().toString());
     details.put("authorizerUsername", authorization.authorizer().getUserName());
     details.put("delegated", authorization.delegated());
     audit.record("REFUND_POLICY_OVERRIDE_AUTHORIZED", AuditResult.EXITO, Map.copyOf(details));
     if (controlAlerts != null) {
         controlAlerts.detectRefundPolicyOverride(
                 session.getId(), session.getTerminalId(), allocation.getAmount(),
                 allocation.getKind().name(), authorization.authorizer().getId(),
                 authorization.authorizer().getUserName(), authorization.delegated(),
                 auth);
     }
 }
    private Optional<DocumentPayment> resolveMatchingOriginalPayment(
            SalePaymentSession session,
            SalePaymentAllocationKind kind,
            BigDecimal amount) {
        var snapshot = snapshots.deserialize(session.getSnapshot());
        var sourceTicketId = refundSourceTicketId(snapshot);
        return refundPaymentSource(sourceTicketId).getPagos().stream()
                .filter(RefundPaymentAvailability::isMonetaryRefundSource)
                .filter(payment -> originalPaymentMatches(kind, payment))
                .filter(payment -> availableRefundAmount(session, payment)
                        .compareTo(amount) >= 0)
             .findFirst();
 }
 private DocumentPayment resolveAnyOriginalPayment(
         SalePaymentSession session,
         BigDecimal amount) {
     var sourceTicketId = refundSourceTicketId(snapshots.deserialize(session.getSnapshot()));
     return refundPaymentSource(sourceTicketId).getPagos().stream()
             .filter(RefundPaymentAvailability::isMonetaryRefundSource)
             .filter(payment -> availableRefundAmount(session, payment)
                     .compareTo(amount) >= 0)
             .findFirst()
             .orElseThrow(() -> new IllegalArgumentException(
                     "original_payment_refund_balance_not_available"));
 }
    private static boolean originalPaymentMatches(
            SalePaymentAllocationKind kind,
            DocumentPayment payment) {
        return RefundPaymentAvailability.allocationKind(payment) == kind;
    }

    @Transactional(readOnly = true)
    public List<RefundPaymentAvailability.View> refundPaymentAvailability(
            SalePaymentSession session) {
        if (session.getDirection() != SalePaymentSessionDirection.REFUND) {
            return List.of();
        }
        var sourceTicketId = refundSourceTicketId(
                snapshots.deserialize(session.getSnapshot()));
        var ticket = refundPaymentSource(sourceTicketId);
        var activeAllocations = session.getStatus() == SalePaymentSessionStatus.FINALIZED
                || session.getStatus() == SalePaymentSessionStatus.CANCELLED
                ? List.<SalePaymentAllocation>of()
                : session.getAllocations();
        var allActiveAllocations = new ArrayList<>(activeAllocations);
        allActiveAllocations.addAll(activePaymentReservations(ticket, session.getId()));
        return RefundPaymentAvailability.calculate(
                ticket, refundTenders, allActiveAllocations);
    }

    @Transactional(readOnly = true)
    public boolean requiresVoucherOnlyRefund(SalePaymentSession session) {
        if (session.getDirection() != SalePaymentSessionDirection.REFUND) {
            return false;
        }
        return snapshots.deserialize(session.getSnapshot()).lines().stream()
                .anyMatch(line -> line.returnSourceType()
                        == TicketReturnService.ReturnSourceType.GIFT_RECEIPT);
    }
 private BigDecimal availableRefundAmount(
         SalePaymentSession session,
         DocumentPayment payment) {
     if (documentPayments != null) {
         documentPayments.findLockedById(payment.getId()).orElseThrow();
     }
     var alreadySettled = refundTenders == null
             ? Money.euros(BigDecimal.ZERO)
             : Money.euros(refundTenders.refundedAmountByOriginalPaymentId(payment.getId()));
     var reservedInSession = session.getAllocations().stream()
             .filter(allocation -> payment.getId().equals(allocation.getOriginalPaymentId()))
             .filter(allocation -> allocation.getStatus() != PaymentTerminalOperationStatus.CANCELLED
                     && allocation.getStatus() != PaymentTerminalOperationStatus.DECLINED
                     && allocation.getStatus() != PaymentTerminalOperationStatus.ERROR)
             .map(SalePaymentAllocation::getAmount)
             .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
     var reservedInOtherSessions = sessions.findActiveRefundReservations(List.of(payment.getId()))
             .stream()
             .filter(allocation -> !session.getId().equals(allocation.getSessionId()))
             .map(SalePaymentAllocation::getAmount)
             .reduce(Money.euros(BigDecimal.ZERO), BigDecimal::add);
     return Money.euros(payment.getImporte()
             .subtract(alreadySettled)
             .subtract(reservedInSession)
             .subtract(reservedInOtherSessions));
 }

 private CommercialDocument refundPaymentSource(UUID sourceDocumentId) {
     var source = documents.returnPaymentSource(sourceDocumentId);
     return source == null ? documents.find(sourceDocumentId) : source;
 }

 private List<SalePaymentAllocation> activePaymentReservations(
         CommercialDocument ticket,
         UUID excludedSessionId) {
     if (ticket.getPagos().isEmpty()) {
         return List.of();
     }
     return sessions.findActiveRefundReservations(
                     ticket.getPagos().stream().map(DocumentPayment::getId).toList())
             .stream()
             .filter(allocation -> !excludedSessionId.equals(allocation.getSessionId()))
             .toList();
 }
 private Authorization authorizeRefundTenderOverride(
         OperationAuthorizationRequest request,
         Authentication auth) {
     if (operationSecurity == null || audit == null) {
         throw new IllegalStateException("sale_operation_security_unavailable");
     }
     if (request == null) {
         throw new RefundTenderOverrideRequiredException();
     }
     return operationSecurity.authorize(
             SaleOperationCode.REFUND_TENDER_OVERRIDE,
             request.authorizerUsername(),
             request.authorizerPassword(),
             auth);
 }
 private void auditRefundTenderOverride(
         SalePaymentSession session,
         SalePaymentAllocation allocation,
         Authorization authorization,
         Authentication auth) {
     if (authorization == null) return;
     var details = new LinkedHashMap<String, Object>();
     details.put("operationCode", SaleOperationCode.REFUND_TENDER_OVERRIDE.name());
     details.put("paymentSessionId", session.getId().toString());
     details.put("allocationId", allocation.getId().toString());
     details.put("paymentKind", allocation.getKind().name());
     details.put("amount", allocation.getAmount());
     details.put("originalPaymentId", allocation.getOriginalPaymentId().toString());
     details.put("operatorId", authorization.operator().getId().toString());
     details.put("operatorUsername", authorization.operator().getUserName());
     details.put("authorizerId", authorization.authorizer().getId().toString());
     details.put("authorizerUsername", authorization.authorizer().getUserName());
     details.put("delegated", authorization.delegated());
     audit.record("REFUND_TENDER_OVERRIDE_AUTHORIZED", AuditResult.EXITO, Map.copyOf(details));
     if (controlAlerts != null) {
         controlAlerts.detectRefundPolicyOverride(
                 session.getId(), session.getTerminalId(), allocation.getAmount(),
                 "TENDER_OVERRIDE_" + allocation.getKind().name(),
                 authorization.authorizer().getId(), authorization.authorizer().getUserName(),
                 authorization.delegated(), auth);
     }
 }
 private UUID refundSourceTicketId(ApprovedCardTicketSnapshot snapshot) {
     var sourceTicketIds = snapshot.lines().stream()
             .filter(line -> line.originalDocumentLineId() != null)
             .filter(line -> line.cantidad().signum() < 0)
             .map(DocumentLineCommand::returnSourceTicketId)
             .filter(Objects::nonNull)
             .distinct().toList();
     if (sourceTicketIds.size() != 1) {
         throw new IllegalArgumentException("refund_requires_single_source_ticket");
     }
     return sourceTicketIds.getFirst();
 }
 private static boolean hasReturnLines(ApprovedCardTicketSnapshot snapshot) {
     return snapshot.lines().stream().anyMatch(line ->
             line.originalDocumentLineId() != null && line.cantidad().signum() < 0);
 }
 private static boolean hasPositiveSaleLines(ApprovedCardTicketSnapshot snapshot) {
     return snapshot.lines().stream().anyMatch(line ->
             line.originalDocumentLineId() == null
                     && line.lineType() == DocumentLineType.PRODUCT
                     && line.cantidad().signum() > 0);
 }
 private List<PaymentTerminalRefundLineSelection> refundSelections(
         ApprovedCardTicketSnapshot snapshot) {
     var selected = new LinkedHashMap<UUID, BigDecimal>();
     var serials = new LinkedHashMap<UUID, List<String>>();
     snapshot.lines().stream()
             .filter(line -> line.cantidad().signum() < 0)
             .filter(line -> line.lineType() == DocumentLineType.PRODUCT)
             .forEach(line -> {
                 var sourceLineId = Objects.requireNonNull(
                         line.originalDocumentLineId(),
                         "refund source document line");
                 selected.merge(sourceLineId, line.cantidad().abs(), BigDecimal::add);
                 serials.computeIfAbsent(sourceLineId, ignored -> new ArrayList<>())
                         .addAll(line.serialNumbers() == null ? List.of() : line.serialNumbers());
             });
     if (selected.isEmpty()) {
         throw new IllegalArgumentException("refund_requires_source_lines");
     }
     return selected.entrySet().stream()
             .map(entry -> new PaymentTerminalRefundLineSelection(
                     entry.getKey(), entry.getValue(), serials.get(entry.getKey())))
             .toList();
 }
 private PaymentTerminalOperationsService requireRefundTerminalOperations(){if(refundTerminalOperations==null)throw new IllegalStateException("refund_terminal_service_unavailable");return refundTerminalOperations;}
 private void validateIntegratedConfiguration(CardTerminalConfiguration config,SalePaymentSession state,String provider){if(!config.enabled())throw new IllegalArgumentException("payment_terminal_configuration_not_enabled");if(config.mode()!=PaymentCardMode.INTEGRATED)throw new IllegalArgumentException("payment_terminal_configuration_not_integrated");if(config.provider()==null||config.provider()==PaymentTerminalProvider.NONE)throw new IllegalArgumentException("payment_terminal_provider_required");if(!config.terminalId().equals(state.getTerminalId())||!config.storeId().equals(state.getStoreId()))throw new IllegalArgumentException("payment_terminal_configuration_scope_mismatch");if(provider==null||!config.provider().name().equals(provider))throw new IllegalArgumentException("provider_not_configured");if(storePaymentConfigurations!=null){var rules=storePaymentConfigurations.findByStoreId(state.getStoreId()).orElse(null);if(rules!=null&&(!rules.isIntegratedCardEnabled()||!List.of(rules.getAllowedPaymentTerminalProviders().split(",")).contains(config.provider().name())))throw new IllegalArgumentException("payment_terminal_provider_not_allowed");}}
 private PaymentMethod activeMethod(SalePaymentAllocationKind kind){var name=switch(kind){case CASH->"EFECTIVO";case MANUAL_CARD,INTEGRATED_CARD->"TARJETA";case VOUCHER->"VALE";case TRANSFER->"TRANSFERENCIA";default->null;};var company=organization.currentCompany();return name==null||company==null?null:methods.findByEmpresaIdAndNombreAndActivoTrue(company.getId(),name).orElseThrow();}
 private VoucherService requireVoucherService(){if(voucherService==null)throw new IllegalStateException("voucher_service_unavailable");return voucherService;}
 private IssuedVoucher issuedVoucherFor(CommercialDocument ticket) {
     if (voucherService == null || ticket == null) {
         return null;
     }
     return voucherService.issuedFromTicket(ticket)
             .map(voucher -> IssuedVoucher.from(voucher, ticket.getNumero()))
             .orElse(null);
 }
 private static String normalize(String value){return value==null||value.isBlank()?null:value.trim();}
 private static UserAccount requireUser(Authentication a){if(a.getPrincipal() instanceof UserAccount u)return u;throw new IllegalStateException("user_required");}
 static String hash(PosCashController.SaleRequest sale,BigDecimal total){var coupon=sale.promotionalCouponCode();var internalComment=sale.internalComment()==null?"":sale.internalComment().trim();var hasOpenPrice=sale.lines().stream().anyMatch(line->line.openUnitPrice()!=null);var hasSerialNumbers=sale.lines().stream().anyMatch(line->line.serialNumbers()!=null&&!line.serialNumbers().isEmpty());var hasTemporaryNames=sale.lines().stream().anyMatch(line->line.temporaryName()!=null&&!line.temporaryName().isBlank());var canonical=new StringBuilder(sale.previousTicketImport()!=null?"sale-payment-session-v8-previous-ticket-import|":hasTemporaryNames?"sale-payment-session-v7-temporary-name|":!internalComment.isEmpty()?"sale-payment-session-v6-internal-comment|":hasSerialNumbers?"sale-payment-session-v5-serials|":"sale-payment-session-v4-checkout-discount|").append(sale.customerId()).append('|').append(PosCashService.canonicalPreviousTicketImport(sale.previousTicketImport())).append('|');if(!internalComment.isEmpty())canonical.append(internalComment.length()).append(':').append(internalComment).append('|');canonical.append(coupon==null?"":coupon.trim()).append('|').append(sale.checkoutDiscountAmount()==null?"0.00":Money.euros(sale.checkoutDiscountAmount())).append('|').append(sale.quoteFingerprint()==null?"":sale.quoteFingerprint().trim()).append('|').append(Money.euros(total));sale.lines().forEach(line->{canonical.append('|').append(line.productId()).append(':').append(line.quantity().stripTrailingZeros().toPlainString()).append(':').append(line.discount().stripTrailingZeros().toPlainString()).append(':').append(hasOpenPrice?(line.openUnitPrice()==null?"-":Money.euros(line.openUnitPrice()).toPlainString()):"-");if(hasSerialNumbers)canonical.append(':').append(PosCashService.canonicalSerialNumbers(line.serialNumbers()));if(hasTemporaryNames)canonical.append(':').append(PosCashService.canonicalText(line.temporaryName()));});return hashText(canonical.toString());}
 private static String hashText(String value){try{var md=MessageDigest.getInstance("SHA-256");return java.util.HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 public record IssuedVoucher(
         String code,
         BigDecimal amount,
         java.time.Instant issuedAt,
         String originTicketNumber) {
     static IssuedVoucher from(Voucher voucher, String originTicketNumber) {
         return new IssuedVoucher(
                 voucher.code(), voucher.initialAmount(), voucher.createdAt(),
                 originTicketNumber);
     }
 }
 public record Finalization(
         SalePaymentSession session,
         TicketPrintView printTicket,
         IssuedVoucher issuedVoucher) {
     public Finalization(SalePaymentSession session, TicketPrintView printTicket) {
         this(session, printTicket, null);
     }
 }
}
