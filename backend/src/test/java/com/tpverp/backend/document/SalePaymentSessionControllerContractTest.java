package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.*;
import com.tpverp.backend.shared.api.ApiExceptionHandler;
class SalePaymentSessionControllerContractTest {
 @Test void finalizeResponseCarriesTheConfirmedTicketSnapshot() {
  var viewComponents=SalePaymentSessionController.View.class.getRecordComponents();
  assertThat(Arrays.stream(viewComponents).map(RecordComponent::getName)).contains("printTicket");
  assertThat(viewComponents[viewComponents.length-1].getType()).isEqualTo(TicketPrintView.class);
  var service=mock(SalePaymentSessionService.class);var controller=new SalePaymentSessionController(service);var auth=mock(Authentication.class);var id=UUID.randomUUID();var session=SalePaymentSession.reserve(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",BigDecimal.TEN);var snapshot=new TicketPrintView(UUID.randomUUID(),"T-1",Instant.parse("2026-07-15T10:15:30Z"),List.of(),List.of(),BigDecimal.TEN);
  when(service.finalizeSession(id,auth)).thenReturn(new SalePaymentSessionService.Finalization(session,snapshot));when(service.get(id,auth)).thenReturn(session);
  assertThat(controller.finalizeSession(id,auth).printTicket()).isSameAs(snapshot);
  assertThat(controller.get(id,auth).printTicket()).isNull();
 }
 @Test void exposesValidatedSimulatorDiscardContract() throws Exception {var method=SalePaymentSessionController.class.getDeclaredMethod("discardSimulation",UUID.class,SalePaymentSessionController.SimulatorDiscard.class,Authentication.class);assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/simulator-discard");var validator=Validation.buildDefaultValidatorFactory().getValidator();assertThat(validator.validate(new SalePaymentSessionController.SimulatorDiscard("application_shutdown"))).isEmpty();assertThat(validator.validate(new SalePaymentSessionController.SimulatorDiscard("sale_entry_cleanup"))).isEmpty();assertThat(validator.validate(new SalePaymentSessionController.SimulatorDiscard("payment_method_change"))).isEmpty();assertThat(validator.validate(new SalePaymentSessionController.SimulatorDiscard(""))).isNotEmpty();assertThat(validator.validate(new SalePaymentSessionController.SimulatorDiscard("logout"))).isNotEmpty();}
 @Test void exposesReloadAllocationQueryFinalizeAndCancelBehindSalePermission() throws Exception {assertThat(SalePaymentSessionController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/v1/pos/payment-sessions");assertThat(SalePaymentSessionController.class.getAnnotation(PreAuthorize.class).value()).contains("TICKETS_CREATE");assertThat(SalePaymentSessionController.class.getDeclaredMethod("get",UUID.class,Authentication.class).getAnnotation(GetMapping.class).value()).containsExactly("/{id}");assertThat(SalePaymentSessionController.class.getDeclaredMethod("add",UUID.class,SalePaymentSessionController.Allocation.class,Authentication.class).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/allocations");assertThat(SalePaymentSessionController.class.getDeclaredMethod("query",UUID.class,UUID.class,Authentication.class).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/allocations/{allocationId}/query");assertThat(SalePaymentSessionController.class.getDeclaredMethod("finalizeSession",UUID.class,Authentication.class).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/finalize");}
 @Test void mutationsUseAPessimisticSessionLock() throws Exception {var lock=SalePaymentSessionRepository.class.getDeclaredMethod("findLocked",UUID.class).getAnnotation(org.springframework.data.jpa.repository.Lock.class);assertThat(lock.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);}
 @Test void activeRecoveryIsServerScopedByStoreTerminalAndUser() throws Exception {assertThat(SalePaymentSessionController.class.getDeclaredMethod("active",Authentication.class).getAnnotation(GetMapping.class).value()).containsExactly("/active");var query=SalePaymentSessionRepository.class.getDeclaredMethod("findActive",UUID.class,UUID.class,UUID.class).getAnnotation(org.springframework.data.jpa.repository.Query.class).value();assertThat(query).contains("s.storeId=:storeId","s.terminalId=:terminalId","s.userId=:userId");}

 @Test void activeRecoveryReturnsNoContentWhenThereIsNoActiveSession() throws Exception {
  var fixture=httpFixture();
  when(fixture.service.active(any())).thenReturn(Optional.empty());

  fixture.mvc.perform(get("/api/v1/pos/payment-sessions/active"))
    .andExpect(status().isNoContent())
    .andExpect(content().string(""));
 }

 @Test void invalidDiscardBodyReturnsBadRequestWithoutCallingService() throws Exception {
  var fixture=httpFixture();
  fixture.mvc.perform(post("/api/v1/pos/payment-sessions/{id}/simulator-discard",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"operator_cleanup\"}"))
    .andExpect(status().isBadRequest());
  verifyNoInteractions(fixture.service);
 }

 @Test void missingOrOutOfScopeDiscardReturnsNotFound() throws Exception {
  var fixture=httpFixture();when(fixture.service.discardSimulation(any(),eq("application_shutdown"),any())).thenThrow(new NoSuchElementException());
  fixture.mvc.perform(post("/api/v1/pos/payment-sessions/{id}/simulator-discard",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"application_shutdown\"}"))
    .andExpect(status().isNotFound());
 }

 @Test void liveModeDiscardReturnsConflictAndAcceptedDiscardReturnsOk() throws Exception {
  var fixture=httpFixture();var id=UUID.randomUUID();
  when(fixture.service.discardSimulation(eq(id),eq("application_shutdown"),any())).thenThrow(new IllegalStateException("simulator_discard_requires_test_mode"));
  fixture.mvc.perform(post("/api/v1/pos/payment-sessions/{id}/simulator-discard",id).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"application_shutdown\"}"))
    .andExpect(status().isConflict());
  var session=SalePaymentSession.reserve(id,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"hash","{}",BigDecimal.TEN);
  when(fixture.service.discardSimulation(eq(id),eq("sale_entry_cleanup"),any())).thenReturn(session);
  fixture.mvc.perform(post("/api/v1/pos/payment-sessions/{id}/simulator-discard",id).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"sale_entry_cleanup\"}"))
    .andExpect(status().isOk());
 }

 private static HttpFixture httpFixture(){
  var service=mock(SalePaymentSessionService.class);var source=new ResourceBundleMessageSource();source.setBasename("i18n/messages");source.setDefaultEncoding("UTF-8");var validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
  var mvc=MockMvcBuilders.standaloneSetup(new SalePaymentSessionController(service)).setControllerAdvice(new ApiExceptionHandler(source)).setValidator(validator).build();
  return new HttpFixture(service,mvc);
 }
 private record HttpFixture(SalePaymentSessionService service,org.springframework.test.web.servlet.MockMvc mvc){}
}
