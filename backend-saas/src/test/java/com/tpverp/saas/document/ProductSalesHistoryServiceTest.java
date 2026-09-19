package com.tpverp.saas.document;

import static com.tpverp.saas.document.ProductSalesHistoryApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tpverp.saas.document.CommercialDocumentQuery.Scope;
import com.tpverp.saas.license.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProductSalesHistoryServiceTest {
    private final SaasInstallationRepository installations=mock(SaasInstallationRepository.class);
    private final InstallationAuthenticator authenticator=mock(InstallationAuthenticator.class);
    private final ProductSalesHistoryRepository repository=mock(ProductSalesHistoryRepository.class);
    private final ProductSalesHistoryService service=new ProductSalesHistoryService(installations,authenticator,repository);
    private final UUID companyId=UUID.randomUUID(), storeId=UUID.randomUUID();
    private final SaasCompany company=mock(SaasCompany.class);
    private final SaasInstallation installation=mock(SaasInstallation.class);

    @BeforeEach void authenticatedFixture() {
        when(company.getId()).thenReturn(companyId);when(installation.getCompany()).thenReturn(company);
        when(installations.findByCompany_IdAndStore_Id(companyId,storeId)).thenReturn(List.of(installation));
        when(authenticator.requireLinkedInstallation(companyId,storeId,List.of(installation),"token")).thenReturn(installation);
        when(repository.coverage(any(),any())).thenReturn(new ProductSalesHistoryRepository.Coverage(0,null));
    }

    @Test void companyScopeIsDerivedFromVerifiedInstallationAndExactProductCodeIsNotNormalized() {
        when(repository.page(any(),any(),any(),any(),anyInt())).thenReturn(List.of(item()));
        Response response=service.page(request(" 0012 ",null,null,200,null,null),"token");
        assertThat(response.productCode()).isEqualTo(" 0012 ");
        verify(repository).page(eq(Scope.company(companyId)),argThat(filter -> filter.productCode().equals(" 0012 ")),
                any(),isNull(),eq(201));
    }

    @ParameterizedTest @ValueSource(ints={-1,0,201,Integer.MAX_VALUE})
    void rejectsOutOfRangePageSizesBeforeQuery(int size) {
        assertThatThrownBy(() -> service.page(request("P",null,null,size,null,null),"token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test void validatesFilterOrderDatesAndCursorWithoutRunningSql() {
        for(Request invalid:List.of(request(" ",null,null,1,null,null),
                request("P","document;drop table x",null,1,null,null),
                request("P",null,"random",1,null,null),request("P",null,null,1,"not-a-cursor",null),
                new Request(companyId,storeId,"P",LocalDate.of(2026,2,1),LocalDate.of(2026,1,1),null,null,null,null,1,null,null),
                new Request(companyId,storeId,"P",null,null,"UNKNOWN",null,null,null,1,null,null))) {
            assertThatThrownBy(() -> service.page(invalid,"token")).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test void rejectsAuthenticationFailureBeforeAnyQuery() {
        when(authenticator.requireLinkedInstallation(companyId,storeId,List.of(installation),"wrong"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> service.page(request("P",null,null,200,null,null),"wrong"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    @Test void detailExportFailsExplicitlyRatherThanTruncatingWhenMoreThanFiftyThousandRows() {
        when(repository.page(any(),any(),any(),any(),eq(201))).thenReturn(Collections.nCopies(201,item()));
        assertThatThrownBy(() -> service.export(request("P",null,null,null,null,"detail"),"token"))
                .isInstanceOfSatisfying(ProductSalesHistoryService.QueryFailure.class,error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(error.getCode()).isEqualTo("PRODUCT_HISTORY_EXPORT_LIMIT_EXCEEDED");
                });
        verify(repository,times(250)).page(any(),any(),any(),any(),eq(201));
        verify(repository,never()).totals(any(),any());
    }

    @Test void exactlyFiftyThousandRowsExportCompletelyAndComparisonExportSkipsDetailBound() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(repository.page(any(),any(),any(),any(),eq(201))).thenAnswer(invocation ->
                Collections.nCopies(calls.incrementAndGet()==250?200:201,item()));
        Response response=service.export(request("P",null,null,null,null,null),"token");
        assertThat(response.items()).hasSize(50000);
        assertThat(response.nextCursor()).isNull();assertThat(response.hasMore()).isFalse();
        clearInvocations(repository);
        assertThat(service.export(request("P",null,null,null,null,"comparison"),"token").items()).isEmpty();
        verify(repository,never()).page(any(),any(),any(),any(),anyInt());
        verify(repository).totals(any(),any());verify(repository).comparison(any(),any());
    }

    @Test void exportRejectsContinuationAndUnknownView() {
        assertThatThrownBy(() -> service.export(request("P",null,null,null,"cursor","detail"),"token")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.export(request("P",null,null,null,null,"unknown"),"token")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }

    private Request request(String code,String order,String direction,Integer size,String cursor,String view) {
        return new Request(companyId,storeId,code,null,null,null,null,order,direction,size,cursor,view);
    }
    private Item item() {
        return new Item(UUID.randomUUID(),"TICKET","T-1","PAGADO",LocalDate.of(2026,9,19),Instant.parse("2026-09-19T10:00:00Z"),
                storeId,"001","Central",UUID.randomUUID(),1,"P","Product","1","8.20","0","8.20","EUR",null,null,null,true);
    }
}
