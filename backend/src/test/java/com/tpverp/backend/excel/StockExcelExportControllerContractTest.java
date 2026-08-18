package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.security.domain.UserAccount;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class StockExcelExportControllerContractTest {

    @Test
    void exposesBackgroundStockExportEndpoints() throws NoSuchMethodException {
        var mapping = StockExcelExportController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/v1/stock/exports");
        assertThat(StockExcelExportController.class.getAnnotation(PreAuthorize.class).value())
                .contains("STOCK_READ", "GESTION_PRODUCTO", "GESTION_ALMACEN",
                        "GESTION_VENTAS", "VENTA", "hasRole('ADMIN')");

        var create = StockExcelExportController.class.getDeclaredMethod(
                "create", StockExcelExportService.ExportRequest.class, Authentication.class);
        var status = StockExcelExportController.class.getDeclaredMethod(
                "status", UUID.class, Authentication.class);
        var file = StockExcelExportController.class.getDeclaredMethod(
                "file", UUID.class, Authentication.class);

        assertThat(create.getAnnotation(PostMapping.class)).isNotNull();
        assertThat(status.getAnnotation(GetMapping.class).value()).containsExactly("/{jobId}");
        assertThat(file.getAnnotation(GetMapping.class).value()).containsExactly("/{jobId}/file");
    }

    @Test
    void usesStableAuthenticationNameWhenPrincipalIsNotAUserAccount() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "ADMIN", "token", java.util.List.of());

        assertThat(StockExcelExportController.owner(authentication)).isEqualTo("ADMIN");
    }

    @Test
    void usesStableUserIdForAuthenticatedUserAccount() {
        var userId = UUID.randomUUID();
        var user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, "token", java.util.List.of());

        assertThat(StockExcelExportController.owner(authentication))
                .isEqualTo(userId.toString());
    }
}
