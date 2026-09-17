package com.tpverp.backend.ui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GestionSalesOverviewController.class)
@Import({GestionSalesOverviewService.class, GestionSalesOverviewControllerTest.MethodSecurityConfiguration.class})
class GestionSalesOverviewControllerTest {

    private static final String PATH = "/api/v1/gestion/dashboard/data/sales-overview";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 16);
    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID WAREHOUSE_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @MockitoBean private GestionSalesOverviewRepository sales;
    @MockitoBean private WarehouseRepository warehouses;
    @MockitoBean private CurrentOrganization organization;

    @BeforeEach
    void organization() {
        var store = mock(Store.class);
        var company = mock(Company.class);
        when(store.getId()).thenReturn(STORE_ID);
        when(store.getEmpresa()).thenReturn(company);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
    }

    @ParameterizedTest
    @MethodSource("allowedAuthorities")
    void returnsTheCompleteOverviewContractForAuthorizedSalesReaders(String[] permissions) throws Exception {
        when(warehouses.findByStoreIdAndIdIn(STORE_ID, List.of(WAREHOUSE_ID)))
                .thenReturn(List.of(mock(Warehouse.class)));
        when(sales.daily(COMPANY_ID, STORE_ID, WAREHOUSE_ID, DAY.minusDays(1), DAY))
                .thenReturn(List.of(new GestionSalesOverviewRepository.DayAggregate(DAY, new BigDecimal("5.00"), 2)));
        when(sales.topProducts(COMPANY_ID, STORE_ID, WAREHOUSE_ID, DAY, DAY))
                .thenReturn(List.of(new GestionSalesOverviewRepository.TopProduct(
                        PRODUCT_ID, "PRODUCT", "Product name", new BigDecimal("1.500"))));

        mvc.perform(get(PATH).with(user("reader").authorities(authorities(permissions)))
                        .param("from", DAY.toString()).param("to", DAY.toString())
                        .param("warehouseId", WAREHOUSE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-16"))
                .andExpect(jsonPath("$.to").value("2026-09-16"))
                .andExpect(jsonPath("$.previousFrom").value("2026-09-15"))
                .andExpect(jsonPath("$.previousTo").value("2026-09-15"))
                .andExpect(jsonPath("$.storeTimezone").value("Atlantic/Canary"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.current.netSales").value(5))
                .andExpect(jsonPath("$.current.operationCount").value(2))
                .andExpect(jsonPath("$.current.averageAmount").value(2.5))
                .andExpect(jsonPath("$.previous.netSales").value(0))
                .andExpect(jsonPath("$.previous.operationCount").value(0))
                .andExpect(jsonPath("$.previous.averageAmount").value(0))
                .andExpect(jsonPath("$.daily.length()").value(1))
                .andExpect(jsonPath("$.daily[0].date").value("2026-09-16"))
                .andExpect(jsonPath("$.daily[0].netSales").value(5))
                .andExpect(jsonPath("$.daily[0].operationCount").value(2))
                .andExpect(jsonPath("$.previousDaily[0].date").value("2026-09-15"))
                .andExpect(jsonPath("$.previousDaily[0].netSales").value(0))
                .andExpect(jsonPath("$.previousDaily[0].operationCount").value(0))
                .andExpect(jsonPath("$.topProducts[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.topProducts[0].code").value("PRODUCT"))
                .andExpect(jsonPath("$.topProducts[0].name").value("Product name"))
                .andExpect(jsonPath("$.topProducts[0].netQuantity").value(1.5));
        verify(sales).daily(COMPANY_ID, STORE_ID, WAREHOUSE_ID, DAY.minusDays(1), DAY);
        verify(sales).topProducts(COMPANY_ID, STORE_ID, WAREHOUSE_ID, DAY, DAY);
    }

    @ParameterizedTest
    @MethodSource("deniedAuthorities")
    void requiresBothAppAccessAndSalesPermissionBeforeReadingAnyData(String[] permissions) throws Exception {
        mvc.perform(get(PATH).with(user("other").authorities(authorities(permissions)))
                        .param("from", DAY.toString()).param("to", DAY.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sales, warehouses, organization);
    }

    @Test
    void rejectsUnauthenticatedRequestsBeforeReadingAnyData() throws Exception {
        mvc.perform(get(PATH).param("from", DAY.toString()).param("to", DAY.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sales, warehouses, organization);
    }

    @Test
    void rejectsMissingAndMalformedDatesAndWarehouseIdsDuringBinding() throws Exception {
        for (var query : new String[]{"", "?from=2026-09-16", "?to=2026-09-16",
                "?from=invalid&to=2026-09-16", "?from=2026-02-30&to=2026-09-16",
                "?from=2026-09-16&to=2026-09-16&warehouseId=invalid"}) {
            mvc.perform(get(PATH + query).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(sales, warehouses, organization);
    }

    @Test
    void returnsBadRequestForUnsupportedRangesBeforeReadingAnyData() throws Exception {
        for (var range : new String[][]{{"2026-09-16", "2026-09-15"}, {"2024-01-01", "2025-01-01"},
                {"0001-01-01", "0001-01-01"}}) {
            mvc.perform(get(PATH).with(user("admin").roles("ADMIN"))
                            .param("from", range[0]).param("to", range[1]))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(sales, warehouses, organization);
    }

    @Test
    void rejectsAnUnknownOrForeignWarehouseBeforeReadingSales() throws Exception {
        mvc.perform(get(PATH).with(user("admin").roles("ADMIN"))
                        .param("from", DAY.toString()).param("to", DAY.toString())
                        .param("warehouseId", WAREHOUSE_ID.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sales);
    }

    static Stream<Arguments> allowedAuthorities() {
        return Stream.of(Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "GESTION_VENTAS"}),
                Arguments.of((Object) new String[]{"ROLE_ADMIN"}));
    }

    static Stream<Arguments> deniedAuthorities() {
        return Stream.of(Arguments.of((Object) new String[]{"ROLE_USER"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS"}),
                Arguments.of((Object) new String[]{"GESTION_VENTAS"}),
                Arguments.of((Object) new String[]{"APP_GESTION_ACCESS", "GESTION_PRODUCTO"}));
    }

    private static SimpleGrantedAuthority[] authorities(String[] permissions) {
        return Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {}
}
