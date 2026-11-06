package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(ReplenishmentAuthorizationTest.Config.class)
class ReplenishmentAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean ReplenishmentService service() { return mock(ReplenishmentService.class); }
        @Bean ReplenishmentExportService exports() { return mock(ReplenishmentExportService.class); }
        @Bean ReplenishmentController controller(ReplenishmentService service, ReplenishmentExportService exports) {
            return new ReplenishmentController(service,exports);
        }
    }
    @Autowired ReplenishmentController controller;
    @Autowired ReplenishmentService service;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); reset(service); }

    @Test void salesCanRequestButCannotManageWithoutWarehousePermission() {
        authenticate("VENTA");
        var command = new ReplenishmentController.Create(UUID.randomUUID(),UUID.randomUUID(),BigDecimal.TEN,"Reposición");
        controller.create(command);
        verify(service).create(command.productId(),command.warehouseId(),command.quantity(),command.reason());
        assertThatThrownBy(() -> controller.list(null,null,null,null,null)).isInstanceOf(AccessDeniedException.class);
        var item = new ReplenishmentService.Item(UUID.randomUUID(),0);
        assertThatThrownBy(() -> controller.status(new ReplenishmentController.Status(List.of(item),true))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(new ReplenishmentController.Selection(List.of(item)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.export("xlsx","es",null,null,null,null,null,null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void warehouseManagersCanManageAndUnrelatedPermissionsCannotCreate() {
        authenticate("GESTION_ALMACEN");
        controller.list(null,null,null,null,null);
        verify(service).listFiltered(null,null,null,null,null,null);
        var item = new ReplenishmentService.Item(UUID.randomUUID(),0);
        controller.status(new ReplenishmentController.Status(List.of(item),false));
        verify(service).changeStatus(List.of(item),false);
        authenticate("CUSTOMERS_READ");
        assertThatThrownBy(() -> controller.create(new ReplenishmentController.Create(UUID.randomUUID(),UUID.randomUUID(),BigDecimal.ONE,"Reposición")))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("TEST","test",
                List.of(new SimpleGrantedAuthority(authority))));
    }
}
