package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

class WarehouseOutputControllerContractTest {

    @Test
    void exposesWarehouseOutputApiWithMethodSecurity() {
        assertThat(WarehouseOutputController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/warehouse-outputs");
        assertThat(Arrays.stream(WarehouseOutputController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PreAuthorize.class)))
                .hasSize(5);
    }

    @Test
    void exposesListCreateUpdateDeleteAndConfirmEndpoints() throws NoSuchMethodException {
        var list = WarehouseOutputController.class.getDeclaredMethod("list", Integer.class, String.class);
        var create = WarehouseOutputController.class.getDeclaredMethod(
                "create", WarehouseOutputCommand.class, org.springframework.security.core.Authentication.class);
        var update = WarehouseOutputController.class.getDeclaredMethod(
                "update", UUID.class, WarehouseOutputCommand.class);
        var delete = WarehouseOutputController.class.getDeclaredMethod("delete", UUID.class);
        var confirm = WarehouseOutputController.class.getDeclaredMethod(
                "confirm", UUID.class, org.springframework.security.core.Authentication.class);

        assertThat(list.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(list.getGenericReturnType().getTypeName())
                .contains("PagedResult", "WarehouseOutputView");
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(create.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.CREATED);
        assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly("/{id}");
        assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly("/{id}");
        assertThat(delete.getAnnotation(ResponseStatus.class).value()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(confirm.getAnnotation(PostMapping.class).value()).containsExactly("/{id}/confirm");
        assertThat(Arrays.stream(update.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(PathVariable.class)))
                .hasSize(1);
        assertThat(List.of(list, create, update, delete, confirm))
                .allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                        .contains("GESTION_ALMACEN", "hasRole('ADMIN')")
                        .doesNotContain("GESTION_PRODUCTO", "WAREHOUSE_OUTPUTS_"));
    }
}
