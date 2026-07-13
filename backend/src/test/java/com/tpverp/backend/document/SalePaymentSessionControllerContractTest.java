package com.tpverp.backend.document;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
class SalePaymentSessionControllerContractTest {
 @Test void exposesReloadAllocationQueryFinalizeAndCancelBehindSalePermission() throws Exception {assertThat(SalePaymentSessionController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/v1/pos/payment-sessions");assertThat(SalePaymentSessionController.class.getAnnotation(PreAuthorize.class).value()).contains("TICKETS_CREATE");assertThat(SalePaymentSessionController.class.getDeclaredMethod("get",UUID.class,Authentication.class).getAnnotation(GetMapping.class).value()).containsExactly("/{id}");assertThat(SalePaymentSessionController.class.getDeclaredMethod("add",UUID.class,SalePaymentSessionController.Allocation.class,Authentication.class).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/allocations");assertThat(SalePaymentSessionController.class.getDeclaredMethod("query",UUID.class,UUID.class,Authentication.class).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/allocations/{allocationId}/query");assertThat(SalePaymentSessionController.class.getDeclaredMethod("finalizeSession",UUID.class,Authentication.class).getAnnotation(PostMapping.class).value()).containsExactly("/{id}/finalize");}
 @Test void mutationsUseAPessimisticSessionLock() throws Exception {var lock=SalePaymentSessionRepository.class.getDeclaredMethod("findLocked",UUID.class).getAnnotation(org.springframework.data.jpa.repository.Lock.class);assertThat(lock.value()).isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);}
}
